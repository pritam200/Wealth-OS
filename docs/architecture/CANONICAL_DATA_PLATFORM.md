# Canonical Financial Data Platform

Package: `backend/src/main/java/com/marketai/dataplatform`. API: `/api/data/**`. UI: Needs Review → **Data Platform**.

> Financial institutions and authoritative sources are the source of truth. Email is an ingestion signal. AI inference is never authoritative.

## 1. Assessment of what existed

Gmail was the only ingestion path. A parsed email was booked straight into the legacy `Transaction`/`Holding` tables, so an email was treated as a fact: a mis-parsed or forwarded message changed holdings, a missing email left a silent gap, and nothing could say how trustworthy a number was. The existing `TransactionFingerprinter`/`ParsedEmailImporter` dedup, `FinancialLedger` replay and `ReconciliationCenter` are kept; they work on the legacy tables and are untouched. The new platform sits beside them and is fed by them.

## 2. Pipeline

```
Institution / AA / Broker / CAS / Statement / Email
   → RawFinancialData        (encrypted payload, idempotency key, schema version, status)
   → Normalization           (RecordNormalizer per schema → NormalizedTransaction / NormalizedHolding)
   → Validation              (RecordValidator: reject with a reason, never guess)
   → Matching / dedup        (TransactionMatcher: explainable decision)
   → Reconciliation          (ReconciliationEngine over every source of one transaction)
   → CanonicalTransaction    (+ TransactionSource rows: many sources → one transaction)
   → HoldingSnapshot         (LEDGER_CALCULATED vs INSTITUTION_REPORTED) → HoldingReconciler → LedgerIssue
   → data-quality score, portfolio / net worth / AI context
```

Each stage is a separate class and each record is processed in its own transaction, so one bad record is marked `FAILED` and retried, never blocking the batch.

## 3. Source hierarchy

| Rank | Source | Base confidence | Authoritative |
|---|---|---|---|
| 1 | Account Aggregator | 1.00 | yes |
| 2 | Broker API | 0.98 | yes |
| 3 | Depository / CAS | 0.97 | yes |
| 4 | Official statement | 0.93 | yes |
| 5 | Email | 0.75 | no |
| 6 | Manual | 0.70 | no |
| 7 | AI inference | 0.40 | no |

A lower rank never overwrites a higher one. Where a weaker source disagrees, the authority's figures are used and the disagreement is recorded as an auto-resolved issue.

## 4. Matching (explainable, ordered)

1. Same source redelivery (same source/provider/reference): updates in place.
2. Exact cross-source reference, sanity-checked on asset, family and date.
3. Composite: same asset, same type family (a SIP may be reported as a BUY), date ±2 days, quantity ±0.0005, amount ±₹1 or 0.5%. A date offset needs exact quantity and amount.
4. One source reports an event once: two look-alikes from one source without differing references become a **possible-duplicate issue** (both kept). Differing references prove two events (partial fills).

Equal amounts alone never merge; different funds never merge. Every decision stores its reason on the `TransactionSource` and is shown in the UI.

## 5. Reconciliation statuses

`VERIFIED` (an authoritative source agrees) · `MATCHED` (two distinct weak sources agree, or a user confirmed) · `PARTIALLY_MATCHED` (a weaker source differs from the authority, authority used) · `PENDING` · `UNCONFIRMED` (weak-only after a 7-day grace period, or not listed by an authoritative feed that covers its date) · `MISSING` (an authoritative source reports it, weaker-source history around it does not have it) · `DUPLICATE` · `CONFLICT` (equal-rank or weak sources disagree and nothing can settle it).

Confidence = best (source base × record quality) + 0.05 per further independent source type, capped at 1.0, capped at 0.5 under a conflict.

**Data-quality score** = verified ÷ (verified + matched + partially matched + pending + unconfirmed + conflict + missing). Duplicates are excluded; an empty ledger has no score (shown as "—", not 0 or 100). Computed per portfolio, account and holding, and deterministic — no AI.

## 6. Holdings

`HoldingService` replays the ledger (weighted-average cost; splits, bonus, oversell and incomplete history are flagged, not hidden) into a `LEDGER_CALCULATED` snapshot and stores each institution-reported position as `INSTITUTION_REPORTED`. A difference raises `HOLDING_MISMATCH` with severity (>0.5% HIGH, >0.05% MEDIUM, else LOW) and ranked suspected causes (missing transaction, duplicate, switch, dividend reinvestment, corporate action, stale data…). Nothing is changed automatically.

## 7. Resolution

Reconciliation Center actions (`POST /api/data/issues/{id}/action`): Review, Confirm, Reject, Merge, Ignore, Mark manual adjustment. Rejected and merged transactions are kept with an audit record; nothing is deleted. A manual adjustment is a visible `MANUAL` transaction with the user's note.

**Confirm books into the portfolio.** Confirming a `MISSING` or `UNCONFIRMED` purchase/sale also adds it to the legacy portfolio (`LegacyApplyService`), through `PortfolioService.addHolding` / `sellHolding`, so oversell, future-date and ISIN-clash checks still apply. It runs in its own transaction: if the portfolio refuses, the ledger confirmation stands and the reason is stored in the issue's resolution note. The legacy row carries provenance `canonical-<id>` and the canonical entry stores the legacy id, so the backfill cannot book it twice. This only ever happens on an explicit confirmation; the platform never rewrites the portfolio on its own. 

**Other events, on explicit request** (`POST /api/data/transactions/{id}/add-to-portfolio`, the button in the transaction detail; never automatic): a split, bonus or **merger** goes through the portfolio's corporate-action paths (a merger needs the surviving symbol, now carried as `newSymbol` from email or a CSV `new symbol` column, plus the ratio); **interest/dividend** becomes an income entry (gross, TDS beside it; linked rather than duplicated if the same credit was already booked from email); **FD opened** is added to the deposit tracker only if the source reported the rate and maturity date (never guessed; RDs are not added); **FD payout** closes the single active deposit at that bank (narrowed by maturity date when several), and is refused when it is ambiguous or far below the principal. The result is stored on the entry (`portfolioRef`) so it cannot be booked twice.

## 8. Providers (Account Aggregator abstraction)

`FinancialDataProvider` (fetch) and `ConsentCapableProvider` (create/status/revoke consent, parse callback) are the seam for a real AA / broker adapter. `ProviderRegistry` discovers them. Consent lifecycle: `REQUESTED → PENDING_APPROVAL → APPROVED | REJECTED | REVOKED | EXPIRED`. Callback endpoint `/api/data/callbacks/{providerId}` is public but returns **401 unless the adapter verifies the callback**.

For development, `PUT /api/data/mock/fixtures` sets what the calling user's next mock sync returns (exists only when the mock flag is on).

`MockAccountAggregatorProvider` exists only when `wealthos.data.providers.mock.enabled=true` (default **false**), contains **no built-in data** (tests inject fixtures), reports mode `MOCK`, and is never counted as an institution source.

**No live AA / broker adapter is included.** A real one needs an FIU/TSP registration, credentials and a signature scheme that cannot be built or tested here. Adding one is: implement the two interfaces, a `RecordNormalizer` for its payload (or emit `aa-fi-v1`), and register it as a bean.

## 9. Email, backfill, imports

* `EmailSignalRecorder` records each email the importer books as an `EMAIL`-source record (extracted fields only, long digit runs masked, no body/subject). It runs in its own transaction and never throws, so it cannot affect the legacy import. The hook is one line in `ParsedEmailImporter`.
* `LegacyBackfillService` copies legacy transactions into the ledger (`EMAIL` if provenance shows an email origin, else `MANUAL`), using the importer's line fingerprint as the record id so a later live record for the same row is recognised. Idempotent; legacy tables are not modified. Rows with no institution go to an institution-less placeholder account that the matcher treats as compatible with any specific account, so a later institution report merges into them.
* CSV import (`POST /api/data/import/csv`): same pipeline, source `STATEMENT`/`CAS`/…, per-row idempotent ids, identical rows within a file are kept as separate transactions, leading formula characters are stripped from text cells. Excel goes through `/api/data/import/file`; PDF statements continue via the existing document pipeline.

## 10. Family

`Ownership` = INDIVIDUAL / JOINT / FAMILY. Individual is private; JOINT is visible to co-owners; FAMILY is visible to family members who have `canViewFamily` and only when the owner has turned sharing on. Viewing a family account does not allow editing it. Data health and transaction lists are scoped through `FamilyAccessService`. The **Family & sharing** tab creates families, adds members by email, toggles sharing, removes members, and sets each account to Only me / My family / Joint (co-owner by email).

## 11. AI context

* `ResearchContextBuilder` adds, for stock and fund research, the portfolio data-quality score, whether an institution source exists, the holding's verification state/source/last-verified/mismatch, and data warnings. `ResearchOrchestrator` appends a rule telling the model to state uncertainty for unverified positions (only when those facts exist).
* `HoldingVerificationOverlay` adds read-only `verificationState`, `verificationSource`, `lastVerifiedAt` and `institutionQuantity` to portfolio holdings (shown as a badge in the stock and mutual-fund tables). A holding's own quantity is never replaced by the institution's; both are shown. For a fund held in several accounts the most concerning state wins.
* `PortfolioContextService` adds ledger-quality statements to `dataGaps` (which already drive the recommendation caveats) only for users who have a canonical ledger.

## 12. Security & observability

Raw payloads are AES-GCM encrypted with the existing `PasswordCipher`. Credentials, OTPs and PINs are never stored. Payloads are not logged; provider errors are truncated. Structured events (`IngestionEvents`): `sync_started/completed/failed`, `record_received/deduplicated/failed`, `transaction_created/reconciled/conflict`, `missing_transaction`, with an optional Micrometer counter. All state changes are written to `LedgerAuditEvent`.

## 13. Migration

Hibernate `ddl-auto: update` creates the new tables additively; nothing existing is altered. Rollback is [canonical_data_platform_rollback.sql](canonical_data_platform_rollback.sql) (reviewed, never auto-run), which drops the new tables (`financial_accounts`, `financial_assets`, `canonical_transactions`, `transaction_sources`, `raw_financial_data`, `transaction_candidates`, `holding_snapshots`, `ledger_issues`, `ledger_audit_events`, `data_connections`, `consent_records`, `financial_sync_runs`, `families`, `family_members`, `account_members`) — the legacy tables are the working copy and are unchanged. Backfill on startup is off by default (`wealthos.data.backfill-on-startup`) and also available from the UI.

## 14. Honest limitations

* **No live AA / broker integration.** Only the abstraction and a MOCK provider; a real adapter needs FIU/TSP registration, credentials and a signature scheme that cannot be built or tested without them.
* Portfolio and net-worth totals still read the legacy tables. They change only when the user confirms a missing transaction (above) or uses the existing portfolio screens; verification is shown beside the figures, never in place of them.
* Purchases, sales, splits, bonuses and mergers can be booked into the portfolio, interest/dividends into income, FDs into the deposit tracker — all only on the user's action. Recurring deposits, and any event whose rate, maturity date, ratio or surviving symbol the source did not report, stay in the ledger with the reason shown.
* Excel statements: `POST /api/data/import/file` accepts .csv/.xlsx/.xls (5 MB). The first sheet is converted to CSV (formulas are never evaluated, dates become ISO) and then follows the CSV path. Apache POI `poi-ooxml` 5.4.0 was added to `pom.xml` for this.
* Foreign currency is carried as a field; no FX conversion is performed.
* PDF statements still enter through the existing document pipeline; CSV and Excel use the new import path.
* Schema is created by Hibernate `ddl-auto: update` (the project has no Flyway); rollback is dropping the new tables.
* Verified in a browser against a scratch backend on in-memory-style H2 (connections, health, reconciliation, confirm-books-to-portfolio, backfill idempotency, family sharing, holding badge). Not exercised: live Gmail, PostgreSQL, or a real AA.

## 15. Tests

`dataplatform/pipeline`: matcher, confidence/reconciliation, ledger replay, quality scoring, normalizers. `dataplatform/service` (DataJpaTest, real commits): email→candidate, multi-source merge, idempotency (AA, email, statement, CSV, sync), missing-SIP scenario, UNCONFIRMED coverage and grace sweep, holding mismatch + manual adjustment, reversal/cancel, partial fills, duplicates + merge, asset behaviours (SIP/STP/SWP/switch/dividend reinvestment/split/bonus/FD), consent lifecycle, callback verification, retry-failed, family access, backfill.
