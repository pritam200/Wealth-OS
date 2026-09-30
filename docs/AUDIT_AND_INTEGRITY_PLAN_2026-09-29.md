# Wealth OS — repository audit and financial-integrity plan (2026-09-29)

Goal: every legitimate financial event is recorded exactly once, or the app says explicitly why it
could not be recorded safely. Every displayed number is traceable to source data.

This document is the audit result and the order in which it is being fixed. Items are ticked as
they land.

## 1. Current architecture (as found)

| Concern | Where it lives today | Verdict |
|---|---|---|
| Ingestion | One pipeline: `GmailSyncService` → `EmailLLMParserService` (Gemini, Ollama fallback) → `ParsedEmailImporter`. No provider regex parsers remain. | Right shape; defects below |
| Dedup | `processed_emails` (message id), `pending_pdfs` (attachment + text hash), `imported_transaction_fingerprints` (event fingerprint + UTR), content matchers in the importer | Gmail-vs-Gmail only; manual entry bypasses it |
| Holdings | `transactions` (BUY/SELL) replayed by `PortfolioService.recomputeFromLedger` | Right idea; corrupting bugs below |
| Cash / transfers | `ledger/` = `cash_accounts` + `ledger_transfers`, written only by the manual ledger UI | Parallel, not a source of truth |
| Net worth | `PortfolioContextService.build` (`/api/wealth/summary`) | Canonical, but several pages re-derive numbers |
| Reconciliation | `ReconciliationService` + 14 registered checks (pull only), `ReconciliationReportService` for email counts | Exists; not persisted, not per document |
| Schema | Hibernate `ddl-auto: update`; `database/schema.sql` covers 12 of ~45 tables and has drifted | No migrations, no guaranteed constraints |

Decision: **keep the existing pipeline and tables and make them the canonical ledger.** Don't build
a parallel "financial_events" database. The fingerprint table is the event identity for every
writer (manual entry included), and every domain row carries provenance. This is the smallest
change that gives one source of truth without a risky migration of all history.

## 2. Findings and fix order

### Phase 1 — data is being lost or corrupted today (P0)

- [x] 1.1 Selling a whole position deletes the holding, and the cascade deletes its entire BUY/SELL history (`PortfolioService.recomputeFromLedger`).
- [x] 1.2 A capital loss is booked as a positive "Capital Gain" income row (`sellHolding`), so tax goes up on a loss.
- [x] 1.3 Every sell and redemption is dated today, not on the trade date. That breaks tax year, holding period, XIRR, and sell dedup (`sellHolding`, `RedemptionService`).
- [x] 1.4 Income and tax summaries cast an `IncomeSource` enum to `String` → ClassCastException (`IncomeService.summary`, `TaxService.summary`).
- [x] 1.5 Approving a review item books nothing: items are queued as `UNKNOWN`, the email is then marked resolved, and the transaction is lost. EDIT rebuilds only amount, date and party, so trades, MF, FD and RD can never be approved.
- [x] 1.6 `/api/gmail/resync` deletes every fingerprint and processed-email row *before* checking the sync lock, then runs a 365-day sync inside one HTTP transaction.
- [x] 1.7 A sync job is marked SUCCEEDED even when messages failed; there are no PARTIAL or RECONCILIATION_REQUIRED states.
- [x] 1.8 Closing an FD or RD twice books its interest twice; nothing guards the status.
- [x] 1.9 multipart/alternative emails send both the text and HTML parts to the LLM, so the same line can be extracted twice.
- [x] 1.10 PDFs with no password are never opened when no hint or saved password exists; they sit in PASSWORD_FAILED.
- [x] 1.11 Decrypted statement text (PAN, account numbers) is logged at INFO, stored in `pending_pdfs.text_snippet`, and returned by `/pending-pdfs` and `/debug-pdf`.
- [x] 1.12 Silent duplicates were counted as "imported". The importer now reports IMPORTED / DUPLICATE / CONFLICT and syncs count them apart. *Still open (Phase 5):* an identical trade from a second email is treated as the same trade (a contract note plus its confirmation), because the order/trade number isn't extracted yet.
- [x] 1.13 A card payment with no parsed date is booked on today's date.
- [x] 1.14 Frontend FD/RD section totals include CLOSED and MATURED_RENEWED deposits → renewed FDs counted twice.
- [x] 1.15 Planner: one SIP can match the same plan line twice (transfer + recurring completion), and a GET writes matches.
- [x] 1.16 Refund/reversal approved from review is booked as income.
- [x] 1.17 Scanned (image-only) PDFs are retried through the LLM on every sync, forever.

Also found and fixed in Phase 1: an oversell was silently clamped (now refused with the reason); approving a card-bill payment from review booked it as spend; plan-match evidence had no uniqueness.

Found, scheduled for Phase 4: MF holdings are keyed on the first 30 characters of the fund name, so a fund's Growth and IDCW options can collide into one holding. The fix needs ISIN/AMFI-code identity with a migration of existing holdings.

`common/schema/SchemaPatches` is the stopgap for changes `ddl-auto: update` can't make (widen a column, drop a stale enum check, add a unique index), until Flyway is introduced in Phase 2.

### Phase 2 — one ledger identity for every writer

- [x] Manual expense, income and rent entry is checked against email-imported records: entering by hand what an email already recorded returns the existing record flagged `alreadyRecorded` (the UI offers "Add anyway"). An email for a transaction already entered by hand is linked to that entry (its source email and line key are set) instead of being booked a second time. Counterparty names are compared normalised (`common/ledger/PartyNames`), so "SWIGGY*BLR" and "Swiggy" match. Two hand entries of the same thing are both kept — that is the user's own record. Trades and FDs keep their domain checks (same holding/date/qty/price; same bank/principal/start date).
- [x] **Found and fixed:** `expenses` and `incomes` were unique on (user, source email), so on PostgreSQL every line of a multi-line statement after the first failed to insert. The key is now (user, source email, line fingerprint).
- [x] Provenance on `transactions`, `fixed_deposits`, `recurring_deposits` (embedded `Provenance`: source email, line fingerprint, extraction method MANUAL / EMAIL_LLM / PDF_LLM, confidence). The import log (`imported_transaction_fingerprints`) now records the attachment id, document hash, extraction method and confidence. `GET /api/provenance/{kind}/{id}` and a "View source" panel on expense, income, FD and RD rows.
- [x] Guaranteed uniqueness, applied by `SchemaPatches` (verified against PostgreSQL 16): `expenses`/`incomes (user_id, source_email_id, source_fingerprint)`, `rents (user_id, schedule_id, rent_month)`, `investment_reconciliations (source_kind, source_ref)`, `cas_balance_snapshots (user, folio, scheme, date)` (a resync used to append another copy each time), `pending_pdfs (user, content_hash)` for imported statements. Duplicate derived rows are removed first; a second *paid* rent row is kept as a one-off payment, never deleted. Flyway is not available in the offline build, so `SchemaPatches` remains the migration mechanism for now.
- [x] Cross-instance sync lock: `sync_locks` row per user with a 30-second heartbeat (`SyncLockService`). A lock is taken over only when its heartbeat has stopped, so a long live sync is never reclaimed; stale-job requeue skips users whose lock is live. The worker doesn't start a second job for a user with one running, and a job that finds the mailbox locked is requeued without using up a retry.
- [x] `@Version` on Holding, FixedDeposit, RecurringDeposit, Rent, PlannedInvestment (existing rows back-filled to 0); a concurrent-update conflict returns 409 with an explanation.
- [x] Rent: the list page no longer writes placeholder rows on every read (concurrent reads created duplicates). This month's instalment of each schedule is shown until something is recorded; statuses are PAID / UPCOMING / OVERDUE (past the due day) / MISSED (month over, unpaid).
- [x] Phase 1 leftover: review items accepted as UNKNOWN before the fix (which booked nothing) are reopened, and their emails marked for re-reading.

### Phase 3 — nothing silently incomplete

- [x] Per-document outcome: every email body (`processed_emails`) and statement attachment (`pending_pdfs`) records extracted / imported / duplicate / conflict / needs-review / failed counts and an outcome of SUCCESS, PARTIAL_SUCCESS, RECONCILIATION_REQUIRED, FAILED or NO_TRANSACTION (`DocumentCounts`). Conflicts are now counted apart from duplicates.
- [x] Statement totals: the extractor returns a bank statement's stated total debits/credits and opening/closing balance (span-verified). The app sums the extracted lines and compares them. A mismatch marks the document RECONCILIATION_REQUIRED and raises an `INGESTION_STATEMENT_TOTALS_MISMATCH` issue ("a line may have been missed"). Card-bill arithmetic and CAS closing units already had checks.
- [x] Span verification now checks that the amount is one of the figures in the cited line, and that the date is in the cited line (or stated in the document). Before, it only checked that the cited line existed, so a real line quoted with a wrong amount passed.
- [x] Persisted reconciliation issues (`reconciliation_issues`): OPEN → ACKNOWLEDGED → RESOLVED automatically when the check stops finding them; a returning issue reopens. They are refreshed after every sync and nightly. The Reconciliation Center page (Needs Review → Reconciliation Center) shows: emails read, attachments, transactions found, imported, duplicates prevented, conflicts, waiting for a decision, could not be saved, failed emails, wrong password, waiting for a password, unreadable scans, documents by outcome, open/resolved issues, failed emails with reasons, and background job health.
- [x] Catch-and-continue in the sync path is recorded. PDF unlock errors, a failed bulk unlock, a failed locked-statement count, and a failed processed-email write all become action items in the sync result (and therefore a RECONCILIATION_REQUIRED job). An unlock error is written on the statement itself; a failed classification call is audited. Bulk-unlocked statements are no longer counted as imported transactions.
- [x] Classification audit rows are attributed to the user and email (they were saved with neither).
- [x] Scheduled jobs record their runs (`scheduled_job_health`: last status, last error, consecutive failures), shown in the Reconciliation Center: reconciliation sweep, checks after each sync, MF NAV refresh, holdings rebuild, deposit maturity, scheduled email sync, retry sweep, Gmail push renewal.
- [x] Dashboard: a failed integrity or reconciliation load says so instead of showing "no issues"; portfolio issues are shown in the reconciliation banner when the portfolio banner could not load; the banner links to the Reconciliation Center.

### Phase 4 — one calculation engine, nothing stale shown as current

- [x] Price/NAV "as of" date on holdings (`price_as_of`, set from the quote date, the NAV date, or today for a hand-entered price). Each holding has a valuation basis: MARKET, STALE (stock price older than 4 days, NAV older than 5, or date unknown) or COST. Holdings tables show the price date and mark stale prices; net worth names stale holdings, their value and the oldest price date. Existing prices are not backfilled from `updated_at` (that would pass them as current); they read "date unknown" until the next refresh.
- [x] A zero price is treated as no price: never stored (a zero quote no longer overwrites a good price), and never used as a value. A holding with no usable price is carried at cost — dropping it would understate net worth by the whole position — and is counted and named in the data-quality notes and on the row ("at cost").
- [x] Credit-card dues are a liability: per card, the latest statement's total due less confirmed payments since that statement (older statements are not added again — each carries the previous balance forward). A card with no statement falls back to the entered due amount, and says so. Net worth, total liabilities, the leverage flag and the Net Worth panel include them. Net-worth snapshots now store liabilities, data quality and the named gaps.
- [x] Frontend re-summing removed. New `GET /api/portfolios/combined-summary` totals every portfolio on the server, including stock-only and fund-only totals; the Risk Matrix, Stock Analysis, Mutual Funds and AI Advisor screens use it (the AI Advisor used to look at only the largest portfolio). The allocation ring uses the server's percentages and shows cash. FD and RD tiles use the tracking summary's totals. The Net Worth panel shows the data-quality caveat whenever a figure is incomplete (this is the panel on the Dashboard).
- [x] One XIRR (`XirrCalculator.holdingXirr`, used by the portfolio screens and Today's Actions — which used the statement's stored figure) and one rate table (`CapitalGainsRates`; the recommendation engine had its own 15% STCG). Long-term means held for *more than* 12 months — a unit sold on its first anniversary is short-term (the old check counted it as long-term).
- [x] Uncredited proceeds are flagged (`LEDGER_UNCREDITED_PROCEEDS`, shown in the Reconciliation Center). They are not credited automatically, because the app cannot know which account received the money.
- [x] FIFO lots (`tax/lot/FifoLedger`, one replay for both realised gains and what-if sales). Tax summary: short-term and long-term gains per lot, set off as the Act allows (a short-term loss reduces long-term gains, a long-term loss only long-term), taxed exactly. Only dividends and interest remain a range, because they depend on the slab. Sales with no matching purchase are named, not guessed. The capital-gains export now includes equity sales, one row per lot, with the exemption applied across equity and funds in date order.
- [x] Prices, NAVs and average costs stored to four decimals (`holdings.current_price`, `average_cost`, `transactions.price` widened in place, verified on PostgreSQL 16; no stored value changes).
- [x] Fund identity by ISIN: an import with an ISIN finds the holding carrying that ISIN whatever its name; when two funds' names share their first 30 characters but their ISINs differ (Growth vs IDCW), the second gets its own symbol. A purchase whose ISIN contradicts the holding it would be added to is refused. Existing data: the integrity check reports same-ISIN holdings under different names, and "Merge duplicates" merges them (transactions moved, never deleted) — except where the two rows contain an identical trade, which is the same purchase imported twice; those are listed for the user to resolve first. Limitation: a fund transaction with no ISIN still falls back to the name.
- [x] Category totals exclude own-account transfers and card-bill payments, so they add up to the month's spend; transfers are reported separately.

### Phase 5 — missing event types

- [x] Stocks: split, bonus and merger are booked as ledger rows (`SPLIT` carries the ratio, `BONUS` is nil-cost units dated on allotment) and replayed by the one FIFO engine, so lots keep their purchase dates and cost. A merger into a company already held, and every demerger (the cost split needs the company's apportionment ratio), go to review. Rights are a purchase, a buyback a sale. Brokerage, GST, stamp duty and exchange charges (never STT, s.48) are stored per trade and taken into FIFO cost and proceeds. The broker's trade number is stored on the trade: two identical fills with different numbers are two trades, a re-read of the same number is one (closes the gap left open in 1.12).
- [x] MF: a switch is two legs (redemption + purchase) sharing a link group, so the redemption is not reported as uncredited cash. IDCW payout is dividend income; IDCW reinvestment buys units and also records the dividend (it is taxable though no cash arrived).
- [x] FD/RD: a maturity or premature-closure payout closes the one open deposit it belongs to (matched by bank, then principal, then maturity date — several candidates go to review), on the bank's amount, date and TDS; interest is booked gross with the TDS beside it. An RD closed early counts only the instalments paid. Interest payouts during the term are income with TDS; a TDS-only advice is attached to the interest it came from. The tax summary reports the TDS recorded.
- [x] Bank/card: a refund is a negative expense in the original purchase's category, linked to it (same merchant, within 120 days, not more than is left un-refunded) — never income, and the purchase is never edited; with no purchase to link, review. Fees and charges are spending (Bank Charges). An EMI conversion is held (the purchase is already spending). Own-account transfers: the outgoing leg is an account transfer (excluded from spend); the incoming leg is paired with it (±3 days, same amount) or reported, never booked as income. ATM withdrawals stay spending, as before.
- [x] Image attachments and scanned PDFs are transcribed by a vision model (Gemini, first 8 pages) and then go through the same span-verified extraction, marked `OCR_LLM`. Without a vision model they stay NEEDS_OCR with the reason and are retried once one is configured. Note: this sends the scanned pages to the configured hosted model.
- [x] Scheduled investments: each instalment is judged against the amount in force on its due date (from the change history) and matched to one purchase only (sales never count): COMPLETED, PARTIAL (more than 2% short), FAILED (recorded with a reason), MISSED, PAUSED (schedule paused then), UPCOMING (including the 10-day processing window); nothing falls due after a cancellation.
- [x] Monthly investment plan: each line shows PLANNED → FUNDED (sent, not all invested) → INVESTED (below plan) → SETTLED, with amounts transferred, invested and still not invested. One-off purchases now count as investing evidence (holdings linked to a SIP schedule are left to the instalment matcher, so a purchase is not counted twice); a line funded by a transfer still picks up the investment that followed.

### Existing data

No history is deleted automatically. A read-only classification (VERIFIED / DUPLICATE / CONFLICT /
MISSING / REQUIRES_RECONCILIATION) comes first; any rebuild takes a backup and needs the user's
explicit go-ahead. The cleanup SQL in `DUPLICATE_DATA_CLEANUP_2026-09-23.md` has still not been run.

- [x] Read-only audit (`GET /api/reconciliation/data-audit`, "Existing data audit" card in the Reconciliation Center) classifies every expense, income, trade, holding, FD and RD as VERIFIED, DUPLICATE (with the record it duplicates), CONFLICT, CORRUPTED, MISSING or REQUIRES_RECONCILIATION, with the reason. It also totals how much spending and income duplicates overstate. Lines from one email on the same day are separate statement lines, not duplicates; the same amount and party on the same day from a different source are only flagged for a look. Reasons carry no amounts, so privacy mode still hides figures.
- [x] Backup: `POST /api/reconciliation/backups` snapshots the user's financial tables (expenses, incomes, portfolios, holdings, trades, MF redemptions, FDs, RDs, card statements and payments, fingerprints) as JSON in `data_backups`. It can be downloaded from the card and restored by hand.
- [x] Rebuild (`POST /api/reconciliation/rebuild`) needs confirm `REBUILD` and a backup of the user's own from the last 24 hours that has not been used yet (each backup allows one rebuild). It re-runs the audit and refuses the whole request if any record named is not currently a DUPLICATE, or is a sale. It removes only the ticked duplicates (expenses, incomes, purchases), moves refunds onto the original that is kept, and re-derives every holding's units and average cost from its trades. It never touches anything the user did not tick.
- [x] Verified end to end on a scratch PostgreSQL 16 through the API and the UI: duplicates were found, same-day lines were left alone, every guard refused, and one duplicate buy was removed, taking the holding from 20 units back to 10.

### AI financial investigation

The existing "Ask your data" advisor (`AdvisorService`) was extended rather than duplicated. The model
only picks which question is being asked (and, optionally, a provider or fund named in the question,
kept only if it literally appears there); every figure is computed by `LedgerInvestigator` from the
ledger, the data audit and the reconciliation records, and each answer lists the records it rests on
with a "View source" link to the originating email/document. Rupee figures in answers follow privacy mode.

- [x] Why did my net worth change this month? — last snapshot before the month vs today, split into recorded income, spending (investments and own transfers excluded) and the remainder (valuation changes and anything unrecorded); says so plainly when there is no earlier snapshot. A nightly job (`networth-snapshot`) now records a snapshot for every user, so there is always a baseline.
- [x] Where did my investment plan go / which investments are still pending? — the month's plan review: planned, transferred, invested, transferred-but-not-invested, and each pending line with its stage.
- [x] Show all transactions imported from HDFC this month — email-sourced expenses, incomes and trades, filtered on the sender, subject and party.
- [x] Why is my HDFC MF value different from yesterday? — the two latest stored NAVs, split exactly into the NAV move and the units bought or sold since; notes when the displayed price is older than the latest NAV.
- [x] Find duplicate transactions / find potentially missing transactions — the data audit's DUPLICATE and MISSING/REQUIRES_RECONCILIATION/CONFLICT findings, open reconciliation issues (a deposit already listed by the audit is not repeated) and failed emails.
- [x] Show every source document behind my MF holdings — every trade behind the holdings, counted by origin (email, by hand, none recorded), naming holdings with no trades behind them.
- [x] Also in this pass: `PortfolioService.rebuildHoldingsFromLedger` (added for the rebuild) duplicated `rebuildHoldingsFromTransactions`; merged into the latter, which now skips holdings already matching their ledger.
- [x] Verified on the scratch PostgreSQL with the local Ollama model (qwen2.5:14b) through the API and the UI.

### Final re-review (§30)

Four independent reviews (frontend, portfolio/deposits, ingestion, reconciliation) of the whole change
set raised 38 findings. Fixed:

- [x] Privacy: every rupee figure on the cash-flow forecast, subscriptions, planning tooltips, risk-matrix data gaps, dashboard issues and Reconciliation Center descriptions now follows privacy mode (`Amount`, `MaskedSentence`).
- [x] Subscriptions: a load failure shows an error instead of "none detected". Stale "already recorded" warnings are cleared when the form changes; cancelling an edit clears it; deleting insurance asks first.
- [x] Data audit: a second row from the same email on another day is a DUPLICATE only when the email has more rows than extracted lines (otherwise REQUIRES_RECONCILIATION, or left alone). Incomes are keyed on source and payer too. Trades are DUPLICATE only with the same reference.
- [x] Import: an identical second FD/RD closure closes the second open deposit instead of being dropped; TDS-only advices attach to interest from the same bank only; dividend duplicates are checked on the gross; refunds, own transfers and deposit interest that look repeated go to review instead of being discarded.
- [x] Review queue: a date edit also moves start/payment dates; an UNKNOWN type can't be approved; an approval that records nothing (already recorded / conflict) is refused instead of reported as done.
- [x] Scans are transcribed on every page (up to 40, in batches of 8; longer scans are refused, not read in part). A statement that states activity but yields no lines goes to review and stays retryable.
- [x] Deposits: a renewal books the interest that rolled over (FD: new principal − old; RD: computed corpus − deposits, labelled as computed), since a renewed deposit can't be closed afterwards. Nightly maturity marking skips a row edited concurrently rather than failing the whole run.
- [x] Card dues count only payments after the statement date. Trade ordering is stable within a day (date, then id). A back-dated sale is checked against the ledger as of its date. A SIP plus a one-off purchase in the same plan counts both. Background-job errors are no longer shown verbatim. Duplicate imported statements are marked before the unique index is built. The scheduler has 6 threads.
- [x] 815 backend tests pass; the frontend type-checks.

Not done in this pass (reported, need a decision or a larger change):

- [ ] STCG/LTCG on redemptions is classified from the holding's buy date and average cost, not FIFO lots; debt funds bought after 1 Apr 2023 (s.50AA) are not treated separately.
- [ ] A manual correction is written as a real SELL/BUY pair in the trade ledger rather than an adjustment.
- [ ] Opening the investment plan (GET) writes match rows.
- [ ] Amount corrections in the review queue apply to expenses and incomes, not to trades or MF transactions.
- [ ] Insurance totals are summed in the browser; stock and MF totals don't show the at-cost / stale-price caveat.
- [ ] The data audit runs one query per holding (slow on large portfolios, not wrong).

### LLM Configuration Center

The existing single provider interface, Gemini client, Ollama adapter and router were consolidated, not
duplicated: `LlmProvider` (Gemini, Ollama) behind one `LlmService`, configured only by `LlmConfigService`.

- [x] Settings → LLM Configuration (admin-only: ADMIN_EMAILS, ROLE_ADMIN, or else the first account). Shows the active provider, model and status, with Test Connection, Refresh Models and Save Configuration.
- [x] Gemini: API key stored AES-256/GCM encrypted (`PasswordCipher`), never returned (only "••••" + last four) and never logged. The model, temperature, max tokens and timeout are all configurable. Ollama: base URL, model (discovered from `/api/tags`), temperature, max tokens and timeout. No model name is hard-coded outside first-run defaults.
- [x] One configuration service holds the active provider and model per task, the endpoint, credentials, temperature, max tokens, timeout, retries and fallback. Until the configuration is first saved, the application.yml/environment values apply exactly as before.
- [x] Model per task: email classification, email extraction, document extraction, scan reading, AI advisor, financial analysis, general assistant. Callers name a task; no business code knows the provider.
- [x] Capabilities (text, JSON, vision, native PDF, tools) come from Ollama's `/api/show`, never from the model's name. A model can't be assigned to a task it can't do. Scan reading needs vision, and it can now run locally on an Ollama vision model.
- [x] Test Connection checks reachability, that the model is available, and structured JSON output. It reports "Ollama is not running", "not installed (ollama pull …)", "Gemini API key invalid" and "quota/rate limit", and never shows raw errors.
- [x] Privacy mode: Local AI (nothing is ever sent to a cloud provider, fallback included; scan reading is switched off if no local vision model is installed), Cloud AI, Hybrid (routine email local; statements, scans and analysis on Gemini), or Custom.
- [x] Fallback only when enabled. Retries apply to transient failures only. A fallback is recorded on the completion, in the audit trail and in the processing log. With fallback off, a failure is reported and never answered by another provider.
- [x] Processing log (`llm_call_log`): task, provider, model, prompt version, duration, outcome, error category, fallback and tokens only; no prompts, no content and no user. Kept 90 days. The health dashboard shows requests today, successful, failed, fallbacks, average latency, last success and last error.
- [x] Prompts are centralised in `PromptLibrary` and versioned (`transaction-extraction-v1`, …). A test pins each text to its version. The version is stored in the audit trail and on every imported line (`extraction_version` = provider:model/prompt), and shown under "View source".
- [x] Model change safety: imported emails are never re-read. A partly-read email re-read by a different model or prompt books identical lines as duplicates. A line that differs goes to review and nothing earlier is changed.
- [x] The same validation, duplicate checks and reconciliation run whichever provider answered; providers return text only.
- [x] 836 backend tests pass; the frontend type-checks. Verified on the scratch database with local Ollama through the API and the UI.

Not done: no connection test was run against the real Gemini API. No real key was used, and none should be. The AI audit trail still keeps the redacted prompt and output for each record, because that is what "View source" shows; the new processing log holds none.

## P0 — Zero missed financial transactions from Gmail

Each event the extractor reports ends in exactly one ledger state, with a reason: IMPORTED, DUPLICATE_OF_EXISTING, RESOLVED, REQUIRES_REVIEW, RECONCILIATION_REQUIRED or FAILED_WITH_REASON. An unread part of an email, a totals mismatch and an unreadable attachment each get their own ledger row too.

- [x] Per-event ledger `email_financial_events` (gmail/ledger). It records source email, attachment id and name, document hash, statement provider, LLM provider and model, prompt version, extraction time, confidence, validation status, dedup status, state and reason.
  - Stored evidence has card and account numbers and PAN masked.
  - A re-read updates the same row: the key is document, kind, amount, date and repeat number. A booked event stays IMPORTED, a person's decision is never overridden, and rows are never deleted.
- [x] Review decisions (accept, edit, reject) close the matching ledger rows. Email-level problems can be marked resolved in the Reconciliation Center. Review items can only be decided in the review queue, where accepting one books it.
- [x] Import manifest per email on `processed_emails`: attachments found and processed, per-attachment notes, documents detected, events detected and unresolved, manifest status. It is refreshed after every sync of that email and after every PDF unlock or dismiss. `GET /api/gmail/emails/{id}/manifest` returns it with every event.
- [x] Extraction prompt v2 (`transaction-extraction-v2`):
  - An exhaustive checklist of event kinds, and an instruction never to drop an event whose kind or merchant is unclear.
  - New fields: `status`, `currency`, `amount`, and `statement_totals.transaction_count`.
- [x] Payment status is handled:
  - FAILED, CANCELLED, DECLINED or REVERSED becomes RESOLVED with no money moved, but only when the quoted line says so. Otherwise it goes to review.
  - PENDING goes to review.
  - A foreign amount with no rupee figure goes to review and is never booked as rupees.
- [x] Stated count checked against lines read (e.g. 37 stated, 36 read). A mismatch, like an amount-totals mismatch, sets RECONCILIATION_REQUIRED and adds an open ledger row, where before it only logged a warning. A later matching read closes it.
- [x] Pre-screen widened:
  - More signals: foreign currency, invoice, receipt, bill, premium, cashback, reward, loan, tax, GST, policy, account, card and others.
  - Mail from a known issuer domain always passes.
  - Attachments are never screened.
  - A screened-out body keeps the reason on its email row.
- [x] Every attachment is inventoried:
  - PDFs and images: statement pipeline, as before.
  - CSV, TXT, HTML and XML: downloaded and read like the body, merged into the email's result.
  - Spreadsheets, Word documents, archives and attached emails: named in the manifest, and flagged RECONCILIATION_REQUIRED when the email is financial.
  - Invites, signatures and logos: noted.
- [x] Same payment in body and attachment: the existing same-email check still makes them one event. A clearly different payee with the same amount and date in one email is no longer merged (`ParsedEmailImporter.differentParty`).
- [x] End-of-sync coverage: "✓ Sync Complete" only when nothing failed, no action item remains and no event is unresolved anywhere in the ledger. Otherwise "⚠ Sync completed with reconciliation required", with the counts. The sync result now carries events seen, accounted and unresolved, and outstanding unresolved.
- [x] UI: an "Unresolved email events" card in the Reconciliation Center, grouped by email, with reason, source, model and prompt version for each event. The sync panel shows the headline and event counts.
- [x] Found in live testing and fixed: Ollama was called with its default 4096-token window. The v2 prompt plus a statement (2,347 prompt and 2,212 output tokens) exceeds it, and Ollama drops the start of an over-long prompt without an error. The provider now sets `num_ctx` from the prompt size plus room for the answer (8k to 32k).
- [x] Found in live testing and fixed: the model labelled a salary credit `sub_type: REFUND`. A refund or reversal label now needs refund or reversal wording in the quoted line, otherwise the credit goes to review.
- [x] Live check with local qwen2.5:14b on a 7-line statement email: all 7 events found, and the stated count of 7 matched. 5 imported, 1 failed payment resolved with no money moved, 1 USD charge held for review. Totals the model computed itself (not stated in the email) were rejected by the span check.
- [x] Tests: 864 backend tests pass (ZeroMissedEventsTest, FinancialEventLedgerTest, AttachmentInventoryTest, OllamaContextWindowTest are new); the frontend type-checks. On the scratch database: the ledger table and manifest columns are created, the three endpoints work, and the resolve rules hold (409 for a review item, 404 for another user's row). The UI card was checked in the browser.

Limits, stated plainly:
- Emails already marked IMPORTED before this change are not re-read, so they have no ledger rows. The ledger covers what is read from now on. Re-reading them would call the model again, and with prompt v2 their lines would be treated as a different read.
- Excel files are not parsed; they are flagged for a person, never silently skipped.
- Event identity inside a document depends on the model reading the same amount and date. A re-read that reads a line differently makes a new row, which goes to review because the model-change guard catches it.
