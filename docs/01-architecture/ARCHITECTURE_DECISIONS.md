# Architecture Decision Records

Each ADR reflects a decision actually visible in the code (a comment, a structural choice, or a
migration), not a hypothetical.

---

## ADR-1: Holdings are always derived from the transaction ledger, never hand-set

**Decision**: `PortfolioService.recomputeFromLedger(Holding)` is the *only* method permitted to set
`Holding.quantity`/`averageCost`. It replays every `Transaction` row (BUY/SELL) in date order to
arrive at a weighted-average cost basis.

**Why**: A holding's displayed quantity/cost must always be traceable to a specific, auditable set
of buy/sell events. If a UI or API allowed directly setting these fields, the number could drift
from what actually happened — the exact failure mode the standing rule ("never fabricate, silently
drop, or overwrite financial records") is designed to prevent.

**Consequence**: A nightly `HoldingReconciliationScheduler` re-runs this replay for every user and
logs when the previously-stored value had drifted from what the ledger implies — this is the
system's built-in drift detector.

**Trade-off accepted**: this is weighted-average-cost, not FIFO/LIFO lot tracking. Tax reporting
that requires per-lot cost basis is not supported (`[PLANNED / NOT IMPLEMENTED]`).

---

## ADR-2: One net-worth formula, everywhere

**Decision**: `PortfolioContextService.build()` is the single computation for total assets, net
worth, and asset-class percentages. `/api/wealth/summary` exposes it; every screen that shows a
net-worth figure consumes this endpoint rather than computing its own.

**Why**: consolidating stocks + MF + FD/RD + EPF + cash + other assets − loans into one number, in
one place, guarantees the dashboard, the advisory engine, and any report agree by construction —
rather than by convention across N call sites.

---

## ADR-3: Cash accounts exist specifically to prevent transfer-inflated net worth

**Decision**: `CashAccount` and `LedgerTransferService` were added so that moving money from a
bank account into a mutual fund purchase does not increase net worth by the transferred amount.

**Why** (from the code's own comment in `PortfolioContextService`): before cash was tracked, an
internal transfer looked identical to new income — the destination asset increased in value with
nothing decreasing to offset it. `LedgerTransferService.applyCashEffect` enforces the fix
structurally: source account balance decreases, destination increases, by the same amount, in the
same operation, guarded by an `applied` flag so it can never double-apply.

**Consequence**: any future asset type that money can move *into* (e.g., a new investment
product) must route through `LedgerTransfer` with the correct `destinationType`, or this
invariant breaks again for that asset class.

---

## ADR-4: Idempotency by content fingerprint, not by message ID

**Decision**: `TransactionFingerprinter` hashes the *financial content* of a parsed email
(type, date, amount, symbol/folio/bank, quantity — explicitly excluding Gmail message ID, sender,
and free-text description) into a SHA-256 stored in `ImportedTransactionFingerprint`, checked
before any transaction is written.

**Why**: a forwarded or resent copy of the same email gets a new Gmail message ID but describes
the same real-world transaction. Deduplicating on message ID alone would double-book it. Content
fingerprinting catches this regardless of delivery path.

**Consequence**: per-domain dedup checks (e.g., `isDuplicateExpense`, `isDuplicateTrade`) still
exist as defense-in-depth in case a future import path bypasses the central fingerprint gate —
documented as intentional redundancy, not oversight.

---

## ADR-5: Local LLM by default, with a mandatory confidence floor and human review queue

**Decision**: Email classification falls back to a local Ollama model (`qwen2.5:7b`,
`temperature=0.0`) only when no deterministic parser matches. Anything below 85% confidence, or
of a type that must never be auto-booked (e.g., `INTERNAL_TRANSFER`), routes to
`EmailReviewItem` instead of being imported.

**Why**: an LLM classification is inherently uncertain. Auto-importing a wrong classification
would silently corrupt the ledger — worse than not importing at all. The system treats "the model
didn't return a confidence score" identically to "confidence was too low" (never as implicit
permission to import).

**Trade-off accepted**: local-first means no per-call API cost and no data leaving the machine,
at the cost of depending on a local Ollama install. `Gemini` is available as a config-swappable
fallback provider.

---

## ADR-6: Async job queue for Gmail sync, not an inline request-blocking call

**Decision**: Syncing now goes through `SyncJob` → `SyncJobWorker` (bounded thread pool) →
`SyncJobRunner`, with atomic job claiming via a conditional `UPDATE ... WHERE status = QUEUED`
and automatic retry/stale-job reclaim.

**Why**: the original inline sync blocked the HTTP request for however long a full mailbox scan
took — worse once the local LLM fallback was added, since each unparsed email costs real
classification time. A durable queue survives process restarts and lets the sync run in the
background while the UI shows progress instead of hanging.

**Consequence**: `POST /api/gmail/sync` (inline) still exists for backward compatibility, but
`POST /api/sync/gmail` (async, returns a job to poll) is the intended path going forward.

---

## ADR-7: Incremental sync via Gmail's history API, with a strict monotonic watermark

**Decision**: `GmailIncrementalSyncService` tracks `GmailToken.lastHistoryId` and only ever moves
it forward (`advanceWatermark`), never backward, and only after `history.list` results were fully
paginated ("complete"). A stale/expired historyId (Gmail 404) forces a full re-scan rather than
silently skipping the gap.

**Why**: Gmail's history API can return partial pages, and a historyId can age out of Gmail's
retention window. Advancing the watermark on partial or stale data would create a silent gap in
ingested transactions — the exact class of bug this ADR exists to prevent.

---

## ADR-8: Signal confidence is capped by data availability, not by the score alone

**Decision**: `SignalEngine.confidence = min(|composite score|, confidence ceiling)`, where the
ceiling is the percentage of total factor-family weight that had usable data. A signal computed
from 3 of 5 families (order-book/microstructure is *always* unavailable — no data source exists)
cannot report a confidence higher than what fraction of factors were actually computed.

**Why**: presenting a strong-looking composite score as if it were fully corroborated, when a
whole factor family (e.g., microstructure) was silently unavailable, would overstate the
engine's actual certainty.

**Related**: `SignalEngine` scores by *factor family* (trend/momentum/structure/volume/
volatility) rather than by individual indicator, specifically because correlated oscillators
(RSI/CCI/Stochastic/ROC/MACD-line all measure similar things) would otherwise silently
over-weight momentum under naive per-indicator averaging.

---

## ADR-9: `Long userId` vs. real `@ManyToOne User` — accepted inconsistency, not yet unified

**Decision (implicit — visible as a pattern, not a single commit)**: newer modules (portfolio,
ledger, actions, tracking, sync) use a real JPA `@ManyToOne` relationship to `User`; older
modules (gmail, card, expense, income, redemption, ai/audit, ai/review, goal, networth) use a
bare `Long userId` column with an index but no FK constraint.

**Why this exists**: reflects incremental modernization over time rather than a deliberate
two-tier design. `[PARTIALLY IMPLEMENTED — no migration plan to unify this was found in the
codebase; documented here as a known inconsistency for future architects to resolve
deliberately.]`

**Risk carried**: without an FK constraint, deleting a `User` row would not cascade or block on
these tables — an orphaned row is possible at the DB level (though no code path for deleting a
`User` was found during this audit).
