# Wealth-OS — Gmail Ingestion & Financial Data Integrity: Root Cause Report

**Date:** 2026-09-23
**Method:** Four parallel read-only audits tracing actual code paths, live DB queries (`psql marketai_db`), and file:line citations. No code was changed in this phase.
**Live backlog at time of audit:** 945 `processed_emails` rows, 532 `pending_pdfs` rows, 116 `email_review_items` (100% still PENDING, 0% ever resolved).

---

## Executive summary

The system is architecturally more sophisticated than the review-queue count suggests. Several core primitives are genuinely well-built: three-tier transaction deduplication with a DB-enforced fingerprint constraint, SHA-256 attachment content hashing, a non-brute-force password derivation engine bounded at 4 attempts, a correctly-designed transaction taxonomy that refuses to auto-book ambiguous types, and FD/RD renewal correlation by amount+timing+bank rather than keyword-guessing.

The failures are concentrated in four places:

1. **A parallel, better-designed pipeline (`DocumentStatus`/`FinancialDocument`, `DocumentClassifier`, `ArithmeticValidator`, `NetWorthAttributionCalculator.revaluation`) was built correctly and never wired to a producer.** Four separate correctness components exist, are unit-tested, and have zero production callers. This is the single largest theme in this audit.
2. **Two of the biggest revenue-adjacent gaps are simple missing capability, not logic bugs**: credit-card statements capture only the header total (0 of ~47 line items per statement), and there is no bank-statement (non-CC) parser at all — both fall through to a 6,000-character AI-text fallback that can't see past the first ~20 lines of a multi-page statement.
3. **The retry/terminal-state guard is too narrow.** `!"FAILED".equals(status)` is the only exemption from "already processed, skip forever." Four other terminal-looking statuses (`REVIEW_REQUIRED`, `SKIPPED`, `PASSWORD_FAILED` in some paths, `DUPLICATE_DOCUMENT`) are permanent tombstones with no automatic re-attempt — even after a password is learned, a parser ships, or a registry is updated.
4. **Every safety net is pull-only.** Reconciliation checks, drift detection, and the review backlog are all correct in isolation but run only when a human opens a specific report page. Nothing schedules them, nothing blocks on their findings, and net worth itself has no independent cross-check — its "unexplained" residual is algebraically guaranteed to be zero by construction, making the one integrity assertion designed to catch missing transactions incapable of ever failing.

Live data confirms the queue's real composition: of 447 actionable backlog items, only ~28 (6%) are genuine human judgment calls. The rest are auto-fixable engineering gaps mislabelled as review items — 280 PDFs that decrypted successfully and hit a parser gap, 63 items whose "accept" path is a proven no-op, 48 non-financial attachments that should never have been queued, and 5 blocked by a stale issuer registry.

---

## A1. Gmail Discovery, Classification, Password Resolution, PDF Decryption

### Discovery
- **Recovery-sync gap (HIGH).** `SyncJobRunner.java:116` falls back to a fixed 14-day, 300-message, category-filtered scan on any incremental-sync failure (e.g. a >30-day outage causes Gmail's `history.list` to 404), then **advances the watermark as if the window were fully drained**. Any outage longer than 14 days, or any mailbox denser than 300 messages/fortnight, causes permanent, silent, unrecoverable message loss — with the sync reporting `status: "OK"`.
- The `newer_than:14d (category:primary OR updates OR promotions OR social)` query (`GmailClientService.java:188`) excludes Forums and Spam/Trash entirely.

### Classification
- **The good classifier is dead code.** `DocumentClassifier`/`SenderDomainStage`/`SubjectPatternStage` run in shadow mode only (`SelectionComparator.java:17-27`), their output logged and discarded (`GmailSyncService.java:256-258`). The live decision is a first-match-wins loop over 19 `@Order(1)`-tied parsers plus one `@Order(100)` catch-all — ties broken by undefined classpath scan order.
- **Silent drop when the LLM is unreachable.** If no parser matches and `emailIntelAgent.isEnabled()` is false (Ollama down, a common state), the email is written `status="SKIPPED"` with **no review row created** — and `SKIPPED` is not the one status exempted from the permanent-skip guard, so it's gone forever, even after the model comes back.
- **Live data: 724 of 774 `SKIPPED` `processed_emails` rows have `matched_parser = NULL`** — the single largest bucket in the entire pipeline, invisible to every review queue and every reconciliation check.

### Password resolution
- The derivation engine itself is the strongest code in the audit: PAN-uppercase, DOB-DDMMYYYY (and DDMMYY), 3 PAN+DOB combination variants, and 45+ provider-specific rules, capped at 4 attempts, package-private plaintext accessors, AES-256/GCM at rest. This is genuinely correct and matches the spec's anti-brute-force requirement.
- **`providerKey` is a bare DNS domain**, so one institution gets exactly one remembered password regardless of how many statement types it issues (e.g. HDFC savings-account statement and HDFC credit-card statement share one slot; whichever is learned first blocks the other from ever being learned).
- **A "hint contains the substring 'pan'" heuristic** (`PdfImportService.java:226-235`) can hand one provider's saved credential to an unrelated PDF on the strength of a guessed English sentence.
- **`resetFailedPasswords` deletes working credentials as collateral** when clearing a stuck provider, because the clear operation is keyed at the same coarse domain granularity.
- **DOB-DD-MM-YYYY (hyphenated) is not modelled**, and 2 of 8 declared strategies (`PAN_LOWERCASE`, `DOB_DDMMYY`) are unreachable from any hint/provider rule.

### PDF decryption & completeness
- Every page of a PDF **is** read — there is no page-count truncation. The real truncation is one layer down: the only fallback for an unrecognized statement (`AiEmailExtractor.java:89`) sees a hardcoded 6,000-character prefix, and the partial result it returns is recorded as `IMPORTED` with no completeness check against any document-stated total.
- **No OCR/vision fallback exists anywhere** (confirmed by exhaustive grep). A scanned/image-only PDF: text-strip returns `""` → every parser returns empty → AI extractor sees an empty string → `PdfImportService.java:606-613` sets `status="FAILED"`, **but `unlocked=true`**, so the derived password gets saved as verified-working despite zero characters having been extracted.
- **"Wrong password" and "never attempted a password" are the same status** (`PASSWORD_FAILED`), because when no PAN/DOB is saved, `resolve()` returns an empty candidate list and zero decryption attempts are made before the status is still set to `PASSWORD_FAILED` with the message *"Saved password failed — please update it"* — which is false when nothing was ever saved.
- No attempt counter, no backoff: identical failing candidates are re-derived and re-attempted (with a fresh attachment re-download) on every 30-minute sync, indefinitely.
- A successful manual unlock mass-marks unrelated PDFs in the same PAN-sharing group as `PASSWORD_FAILED` in the same request.

### PAN/DOB vault
- Correctly built: encrypted at rest, never logged, never returned by any endpoint (the DTO is structurally incapable of carrying a value), package-private plaintext accessors.
- **One real defect**: it inherits `PasswordCipher`'s dev-convenience key generation. If `gmail.env` is missing (any container/pure-env-var deployment), the encryption key silently regenerates on every restart, and `hasPan()` tests ciphertext-presence rather than decryptability — so the UI can report "PAN and DOB saved" while every derivation using them silently returns nothing.

### Sender trust
- The design intent (don't block unregistered-but-legitimate domains) is correct and stated in code. But the evaluator's own precedence inverts it: a legitimate issuer whose domain is missing from the 19-entry `IssuerDomainRegistry` almost always **names itself in the display name** — which the code checks *first* and classifies as `IMPERSONATION_SUSPECTED`, not `UNKNOWN_DOMAIN`. Confirmed false positives against real institutions: Kotak Securities, Angel Broking, CAMS (`camsonline.co.in`), HDFC ("smartapply" subdomain) — several of which the *separate* `ProviderPasswordRules` registry already trusts, so the system simultaneously derives a password for a domain it blocks as an attacker.
- **This directly feeds the biggest data-loss bug found in the whole audit (A2 below): the multi-item review-enqueue loop.**

---

## A2. Extraction, Deduplication, Idempotency

### Parser coverage & taxonomy
- 20 parsers, well-organized by institution/type. Two enums (bookable `ParsedEmail.Type`, semantic `EmailIntelType`) deliberately map ambiguous categories (`INTERNAL_TRANSFER`, `MF_SWITCH`, `FD_MATURITY`) to `null` — correctly preventing auto-booking of anything requiring judgment. This is good design.
- **Subscription detection does not exist at all** (confirmed by exhaustive grep) — not partial, absent. Worse, recurring same-amount/same-merchant charges on a known card actively generate false NEEDS_REVIEW duplicates in the fuzzy matcher (see below).
- **Refunds book as unlinked new Income rows**, not as reversals of the original expense — spend analytics overstate refunded merchants' totals.

### Deduplication (3 tiers) — mostly correctly built
- **Tier 1 (message id):** `(user_id, gmail_message_id)` unique constraint, correctly upserted. The FAILED-retry hole the code comments celebrate fixing **is** fixed — but the exemption is only for the literal `"FAILED"`; `REVIEW_REQUIRED` and parser-gap `SKIPPED` are permanent tombstones by the same mechanism.
- **Tier 2 (content hash):** SHA-256, computed before parsing — correctly designed and present (contradicting the audit brief's assumption it might be missing). **Two real bugs**: it hashes the *encrypted* bytes, not decrypted plaintext, directly contradicting its own Javadoc — so the same statement re-encrypted with a different salt evades the check it was built for; and it's an index, not a unique constraint, so it's application-checked only.
- **Tier 3 (transaction fingerprint):** 14-field fingerprint, genuinely richer than date+amount, and **DB-enforced** via a unique constraint coupled to the same transaction as the ledger write — this is the strongest dedup mechanism in the system. **One serious bug**: the PDF import path calls a 4-arg overload that passes `documentText = null`, which silently disables tier-1 rail-reference (UTR/RRN) matching for exactly the documents most likely to carry a UTR (contract notes, statements).
- **Fuzzy matching** exists, is well-weighted, and correctly handles date-drift (±3 days) — but the candidate pool is pre-filtered by **exact** amount before scoring, so a paise-level rounding difference (common with FX/convenience fees) makes a true duplicate invisible to the fuzzy matcher entirely.

### The biggest concrete data-loss findings
- **Credit-card statements: only the header total is captured.** `CardBillParser` produces exactly one row (`totalDue`) per statement; there is no line-item loop anywhere in the codebase. A 47-transaction statement becomes one number; `minimum_due` is declared on the entity but never populated by any parser. This is likely the single largest source of missing transaction data in the system.
- **No bank-statement (non-CC) parser exists at all.** The six per-bank parsers handle only FD/RD-opening emails; the generic `BankTransactionParser` explicitly refuses anything containing "e-statement"/"statement is ready"; multi-page bank statement PDFs fall through to the same 6,000-character AI fallback and typically yield nothing, recorded as one `FAILED` PDF rather than N missing transactions.

### Idempotency at the DB level
- Only 3 of 7 relevant tables have a unique constraint (`processed_emails`, `imported_transaction_fingerprints`, `pending_pdfs`). **`expenses`, `incomes`, `card_statements`, `card_payments` have no DB-level uniqueness at all** — `source_email_id` exists on all four and is unconstrained on all four. `CardStatement`/`CardPayment` additionally have **zero application-level duplicate check** before insert (unlike `Expense`/`Income`, which at least do a TOCTOU-vulnerable in-memory scan). The de facto backstop today is the fingerprint-table constraint plus a shared transaction boundary — correct in the common case, but bypassable by any future code path that writes to these repos directly, and provides no protection across multiple app instances (the sync lock is a `static` in-JVM map).

---

## A3. FD/RD Lifecycle & Statement Reconciliation

### State machine
- Status is a free-text `String`, not a typed enum, on both `FixedDeposit` and `RecurringDeposit` — no DB constraint, no transition validation. The FD entity's own header comment documenting legal states is already stale (omits `MATURED`, which the code writes and reads). Calling `close` twice (double-click, retried request — no idempotency key on the endpoint) books a second interest Income row each time; nothing checks "is this transition legal from the current state."

### Renewal detection — well-engineered core, brittle edges
- Correctly matches by bank + maturity-date proximity (±10 days) + maturity-value tolerance (±5%) — not keyword-guessing. This is good design and matches the spec's requirement.
- Bank match is exact-string-ignore-case ("HDFC Bank" ≠ "HDFC Bank Ltd" — the system's own `UnlinkedRenewalCheck` names this as a known cause of missed links, and has a `normaliseBank` helper the linker itself doesn't use). A null `maturityDate` (permitted; unvalidated on the request DTO) disqualifies a candidate outright.
- **Double-detection race**: the FD import path calls renewal-linking twice for the same new FD, and the second call doesn't check "already linked" before re-scanning — can result in linking the wrong predecessor and leaving one old FD both flagged `MATURED_RENEWED` and orphaned from its true successor.

### Net worth double-counting
- The aggregation-side exclusion (`MATURED_RENEWED` excluded from totals) is correctly implemented. **The failure is entirely at the linking step**: a missed link (e.g. from the bank-name mismatch above) leaves both the old and new FD counted, permanently, until a human opens the reconciliation report — which nothing schedules.
- **A `MATURED` (not yet renewed/closed) FD is counted at full maturity value forever** — this status is not in the exclusion filter, so an FD that matured 8 months ago and was never explicitly closed still contributes its full value to net worth.

### Maturity proceeds
- **No cash is ever credited on maturity.** The only scheduled job (`markMaturedDeposits`, daily) flips status to `MATURED` and does nothing else — no `CashAccount` credit, no scheduled auto-close. Explicit user close only books the interest as Income; the principal simply disappears from net worth with no corresponding cash increase, so net worth *drops* by the full principal on a maturity that made the user money. The code's own `UncreditedProceedsCheck` documents this exact bug but only fires above a materiality floor, only for already-closed rows, only within a 90-day window — it doesn't cover the far more common case (a matured-but-never-closed FD sitting wrong indefinitely).

### FD/RD interest credits
- **Explicitly filtered out and silently discarded.** `BankTransactionParser`'s skip-word list includes "fixed deposit"/"recurring deposit" (deferring to the bank-specific parsers), but those parsers only emit `FD_OPEN`/`RD_OPEN` — none of them handle an interest-credit email. The result: a quarterly "Interest of ₹8,750 credited to your FD" email matches a bank parser's `canParse`, fails its `parse` regex (which expects an opening amount, not an interest figure), and returns nothing — booked nowhere, not even as a review item. Across several deposits and several quarters, this is a real, recurring understatement of taxable interest income feeding directly into the FY capital-gains estimate.

### Credit card statement/payment reconciliation
- **No arithmetic reconciliation exists anywhere** (`RECONCILIATION_FAILED` has zero references in the codebase). What exists is a payment-allocation waterfall against `totalDue` only. `CardStatement.previousBalance` is a persisted column that is **never once compared to anything** — the `previous_due + spends − payments = new_due` identity that would have caught a misread digit is simply never run, even though the correctly-written, unit-tested code to run it (`ArithmeticValidator.statementBalances`) already exists and has zero production callers.
- **Payment-to-card matching falls back to "arbitrary DB row order"** when last-four digits aren't in the email text (common) and two cards share an issuer — silently misattributing a payment to the wrong card, marking one falsely PAID and leaving the other falsely OUTSTANDING (and triggering a false due-date reminder).

### Scheduling
- Every safety net in this domain (`UnlinkedRenewalCheck`, `UnlinkedRdRenewalCheck`, `UncreditedProceedsCheck`, `checkFdRdIntegrity`, `checkNetWorthDrift`, card reconciliation) is **pull-only** — reachable exclusively via a manual `GET`. Zero `@Scheduled` jobs exist in `card/`, `reconciliation/`, `networth/`, or `ledger/`. A missed link from month 1 persists silently for as long as the user doesn't happen to open that report.

---

## A4. Review Queue & Cross-Domain Reconciliation

### Live composition (the "206 items," now 447 across all queues)
There are effectively **five separate review/failure surfaces** that don't share a vocabulary, a table, or a count:
1. `email_review_items` (116 rows, `ReviewStatus` enum) — genuinely only ~28 are ambiguous; 63 are `STATEMENT_ONLY` items whose "accept" path is proven to create zero financial rows (a no-op by design); 5 are blocked by the same stale sender registry as A1; the rest are confidence-threshold misses.
2. `pending_pdfs` (532 rows, free-text status) — **280 rows are `FAILED` with the identical message "unlocked successfully but no transactions were found."** The password worked; the PDF decrypted; no parser understood the text. This is pure parser-coverage gap, concentrated in ~7 providers (mstock, NSE, CAMS, NSDL, Upstox, ICICI, Income Tax portal) — and **every one of those providers also has successfully-imported rows**, meaning this is a within-provider template gap, not an unsupported institution.
3. `processed_emails` with status `REVIEW_REQUIRED`/`SKIPPED` (774 + 111 rows) — 724 of the 774 SKIPPED have no matched parser at all and are invisible to every review queue and every reconciliation check.
4. `financial_documents`/`DocumentStatus` — a correctly-designed 10-state machine with enforced legal transitions, **zero rows, zero production writers**. The reconciliation check that watches it is therefore guaranteed to report clean forever.
5. `DuplicateState` on fingerprints — 3 of 6 states (`POSSIBLE_DUPLICATE`, `CONFIRMED_DUPLICATE`, `RECONCILED`) are declared but never written, by the class's own admission in its Javadoc.

**Net: of 447 actionable backlog items, ~28 (6%) require actual human judgment. The other 94% are engineering gaps that happen to be sitting in a "review" table.**

### Lifecycle — nothing is ever automatically re-processed
- Once an item lands in review, or an email is marked `REVIEW_REQUIRED`/`SKIPPED`, **the retry guard exempts only the literal string `"FAILED"`** — so these are permanent, unless a human clicks the exact UI action, or the only bulk-reprocess endpoint (which itself re-runs the identical parser against identical bytes and will produce an identical failure for anything that isn't a password problem) is manually triggered.
- There is no parser-deployment hook, no capability-improved re-attempt, and no scheduled sweep of any kind.
- `POST /api/gmail/retry-failed` **deletes** the `SKIPPED`/`FAILED` rows it "retries," destroying the audit trail the reconciliation layer's own design philosophy says must never be silently discarded.

### Reconciliation — advisory only, no blocking state, no persistence
- 8 registered checks, all correctly reasoned as *reports, never repairs* — but that design intent has been extended, unintentionally, to *no consequence at all*: `ReconciliationIssue` has no `blocking`/`acknowledged`/`resolvedAt` field and is never persisted; the report is recomputed fresh on every manual `GET` with no dashboard surfacing, badge, or gate. A HIGH-severity finding (net worth understated by ₹5.4L) and a clean report look identical to the dashboard.
- Two of the eight checks (`StalledDocumentCheck`, `TransactionConflictCheck`) watch tables/flags that have zero live rows and are therefore structurally guaranteed to never fire — while the actual 331 broken `pending_pdfs` rows sit outside the entire reconciliation package's import graph (nothing in `reconciliation/` even imports `PendingPdfRepository`).

### Net worth has no independent check
- `NetWorthAttributionService` computes `revaluation = change − known_behaviour` and then `unexplained = change − explained`, where `explained` is defined to already include that same `revaluation` — algebraically, `unexplained` is **always zero**, regardless of what actually happened. This directly contradicts the explicit Javadoc contract of its own collaborator (`NetWorthAttributionCalculator`), which states revaluation "must be supplied, not inferred... otherwise every missing transaction silently becomes market movement" — and that is exactly what happens. The method that would supply it independently (from price × quantity deltas) exists, is correctly written, and has zero callers.
- Concretely: an FD maturity that nets the user +₹40,000 interest but has its ₹5,00,000 principal simply vanish (per A3) is reported to the user as "the market lost you ₹5.4 lakh," with full confidence, because the check designed to catch exactly this can never fail.

### Statement arithmetic reconciliation is written and orphaned
- `ArithmeticValidator.statementBalances`/`tradeReconciles`/`mfReconciles` are correct, unit-tested, and have **zero production callers** anywhere in the codebase — the single most valuable, cheapest-to-wire fix available (see Priority 1 below). For bank statements the gap is deeper: no entity/parser captures opening/closing balance at all, so the operands don't exist yet. For credit cards, 3 of 4 operands (`previousBalance`, `totalDue`, implicitly current charges) are already persisted and simply never compared.

### Sync health reporting
- Four different endpoints in three packages report four incompatible partial pictures. The one that most resembles the spec's target shape (`GmailSyncSummaryDto`) exists **only as a field on a POST response** — it has no GET endpoint, so it vanishes on page refresh, and its `queuedForReview` counts only new-this-run items, never the 116-item standing backlog. The persistent reconciliation endpoint never imports the review-item repository at all. The overall sync `"OK"`/`"ACTION_REQUIRED"` status is derived from two per-run counters and a password-pending count that is currently zero — **so the system reports "OK" while 447 items sit unresolved on disk.**

---

## Cross-cutting theme: four correctly-built components, zero callers

| Component | Would fix | Status |
|---|---|---|
| `DocumentStatus`/`FinancialDocument` state machine | Review/failure conflation across 5 incompatible status surfaces | 0 rows, 0 production writers |
| `ArithmeticValidator.{statementBalances,tradeReconciles,mfReconciles}` | Silent statement-figure corruption (misread digits, OCR errors) | 0 production callers |
| `NetWorthAttributionCalculator.revaluation(qty, priceOpen, priceClose)` | Tautological "unexplained = 0" net worth check | 0 callers from the service that needs it |
| `DuplicateState.{POSSIBLE_DUPLICATE,CONFIRMED_DUPLICATE,RECONCILED}` | Missing conflict/reconciled states | Declared, never written |

This is not primarily a design problem — the correct mechanism for several of the hardest requirements in the P0 spec already exists in this repository, tested, and simply was never connected to a producer.

---

## Recommended fix priority (by financial-integrity risk, cheapest/highest-leverage first)

1. **Wire `ArithmeticValidator` into CC statement import and the (new) bank-statement parser.** Near-zero-risk, catches misread-digit/OCR corruption immediately. Populate `previousBalance` comparison for cards today.
2. **Fix the retry/terminal-state guard** (`GmailSyncService.java:182`) to distinguish true terminal success from `REVIEW_REQUIRED`/parser-gap `SKIPPED`, and add a scheduled sweep that re-attempts backlog items when a new parser version or registry entry ships.
3. **Build the CC statement line-item parser and a bank-statement line-item parser.** Highest-volume missing-data fix in the system.
4. **Fix the multi-item review-enqueue loop** (one `EmailReviewItem` overwritten per item — only the last trade of a multi-trade contract note survives) and the `SenderTrustEvaluator` precedence bug that causes it to fire on legitimate issuers.
5. **Credit FD/RD maturity proceeds to a `CashAccount`** and fix the `MATURED`-forever double-count; add FD/RD interest-credit handling.
6. **Replace the tautological net-worth `unexplained` calculation** with the already-written independent `revaluation()` method.
7. **Consolidate the 5 status surfaces onto the existing `DocumentStatus` state machine** (rather than inventing a sixth), and schedule the existing reconciliation checks instead of leaving them pull-only.
8. **Provider-key granularity fix** for password learning (per-document-type, not per-domain) and the sender-trust registry sync with `ProviderPasswordRules`.
9. DB-level unique constraints on `expenses`/`incomes`/`card_statements`/`card_payments`; fix the content-hash-of-ciphertext bug; fix the PDF-path's dropped `documentText` (disables UTR dedup on PDFs).
10. Subscription detection, refund-linking, fuzzy-match amount tolerance, OCR fallback for scanned PDFs — lower blast-radius, but explicitly required by the spec.

---

*Audit only. No files were modified during this phase. Full agent transcripts with all file:line citations are available in this session's task history.*
