# PROJECT_CONTEXT.md — Indian Markets AI Platform / Personal Wealth OS

Context handoff for continuing development in a fresh Claude session. Read this first, then dive into specific files as needed.

## Project Overview

Personal wealth-management platform ("Personal Wealth OS") for tracking and analyzing an individual's full financial picture: stocks, mutual funds, FDs/RDs, EPF, bank balances, income/expenses, dividends, loans, credit cards. Ingests data from Gmail (broker/bank/AMC notification emails with PDF attachments) in addition to manual entry, computes net worth / asset allocation / P&L / XIRR, and produces AI-driven investment recommendations (buy/hold/sell/reduce/book-profit guidance per holding).

The governing principle for this codebase, set explicitly by the user after a "STOP PATCHING — REWORK THE FINANCIAL CORE" directive:

> **Do not fabricate, estimate, duplicate, silently drop, or overwrite financial records.**

Every number shown anywhere in the UI must come from one validated backend computation — never re-derived independently per screen. FD/RD renewals must never double-count. Every imported transaction needs a source reference and idempotent dedup. Anything the system can't verify must be surfaced as "RECONCILIATION REQUIRED," never silently displayed as if correct.

## Tech Stack

- **Backend**: Java 8, Spring Boot 2.7, Hibernate/JPA, PostgreSQL, Maven
  - JAVA_HOME for this repo: `/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home`
- **Frontend**: React + TypeScript + Vite
- **Infra**: `docker-compose.yml` at repo root (likely Postgres + maybe other services — check file)

## Folder Structure

```
indian-markets-ai-platform/
├── backend/
│   └── src/main/java/com/marketai/
│       ├── portfolio/         (Holding, Transaction, PortfolioService, XirrCalculator, reconciliation-adjacent)
│       ├── recommendation/    (PortfolioContextService — THE canonical net worth/allocation calculator; RecommendationEngine)
│       ├── networth/          (NetWorthService/Controller — historical snapshots)
│       ├── tracking/          (FixedDeposit, RecurringDeposit, TrackingService — FD/RD lifecycle, expenses/income)
│       ├── reconciliation/    (NEW: cross-domain ReconciliationService/Controller)
│       ├── gmail/             (GmailSyncService, ParsedEmailImporter — email ingestion pipeline)
│       └── ... (auth, market-data, mf, etc. — not yet fully audited this session)
│   └── src/test/java/com/marketai/... (mirrors main structure)
├── frontend/
│   └── src/
│       ├── api/               (wealth.ts, tracking.ts, planning.ts, reconciliation.ts, etc. — typed API clients)
│       ├── pages/
│       │   ├── DashboardPage.tsx
│       │   └── tabs/          (Tab6MutualFunds, Tab7RiskMatrix, Tab8MyWealth, Tab16TodaysActions, ...)
│       └── components/wealth/ (FDSection, RDSection, NetWorthTrend, etc.)
├── database/
├── docker-compose.yml
└── README.md
```

Note: `.claude/launch.json` (if present) references `frontend` with `cwd: "../indian-markets-ai-platform/frontend"` — this only works from a session whose project root is a sibling directory; it does NOT work from a session rooted at `/Users/pksingh/Documents/dito`. If you start a fresh session, root it directly at `/Users/pksingh/Documents/indian-markets-ai-platform` to be able to run/preview the frontend.

## Database / Schema (as touched this session)

- `holdings` — quantity, average_cost now **always derived by replaying `transactions`** (never hand-set), except via synthetic "manual correction" transactions.
- `transactions` — the ledger of record for all holdings. BUY/SELL rows with date, quantity, price, notes.
- `fixed_deposits` — status (`ACTIVE`/`MATURED`/`CLOSED`/`MATURED_RENEWED`), closed_date, maturity_amount, renewed_to_id, renewed_from_id.
- `recurring_deposits` — **this session added**: status, closed_date, maturity_amount, renewed_to_id, renewed_from_id (mirrors FD; previously had none of this — RD lifecycle didn't exist before).
- `net_worth_snapshots` — now always populated from server-computed `PortfolioContextService.build()`, never from client-POSTed totals.
- `processed_emails` — Gmail dedup keyed on `(user_id, gmail_message_id)`; content-level dedup (symbol/date/qty/price, or bank+amount+date for FD/RD) also enforced independently in `ParsedEmailImporter`.

## APIs (new/changed this session)

- `GET /api/wealth/summary` (NEW) — `WealthController`, returns `PortfolioContextService.build(userId)` directly. **This is the one source of truth for net worth/allocation/P&L app-wide.**
- `POST /api/networth/snapshot` (CHANGED) — now takes no body; server computes and persists the snapshot itself.
- `POST /api/tracking/rd/{id}/close` (NEW) — mirrors existing FD close endpoint.
- `GET /api/reconciliation/report` (NEW) — `ReconciliationController`, returns all cross-domain reconciliation issues (portfolio + FD/RD + net-worth drift).

## Important Architectural Decisions

1. **Single source of truth**: `PortfolioContextService.build(userId)` (`backend/.../recommendation/service/PortfolioContextService.java`) is the ONLY place net worth / asset allocation / equity P&L is computed. All controllers and all frontend screens must call through to it (directly or via `/api/wealth/summary`) — never recompute independently.
2. **Ledger as source of truth**: `Holding.quantity`/`averageCost` are never directly overwritten. `PortfolioService.recomputeFromLedger(Holding)` replays all `Transaction` rows every time a holding changes (buy/sell/merge). Manual corrections (`updateHolding`) are modeled as synthetic offsetting SELL + corrective BUY transactions, not direct field writes — so the transaction ledger is always the ground truth and nothing is silently overwritten.
3. **FD/RD renewal linking**: same-bank, maturity-date-within-window, amount-within-tolerance heuristic (`detectAndLinkRenewal` for FD, `detectAndLinkRdRenewal` for RD) links an old matured deposit to its renewal, marking the old one `MATURED_RENEWED` so it's excluded from net worth (prevents double-counting principal). Runs on **every** creation path now (manual add + Gmail import), not just Gmail import as before.
4. **Status lifecycle**: `ACTIVE → MATURED → CLOSED` or `ACTIVE → MATURED → MATURED_RENEWED`, identical state machine for both FD and RD now. Nightly `@Scheduled` job (`DepositMaturityScheduler`) flips `ACTIVE` past maturity date to `MATURED` automatically.
5. **Reconciliation, never silent wrongness**: `ReconciliationService.checkAll(userId)` aggregates portfolio integrity issues (mismatched tickers, duplicate symbols), FD/RD lifecycle issues (matured-idle >30 days, orphaned renewal links), and net-worth drift (server-computed value vs. last persisted snapshot, tolerance = max(₹1000, 1%)). Surfaced via `GET /api/reconciliation/report` and a `ReconciliationBanner` on the Dashboard.
6. **One realized-gain record per MF sale**: `sellHolding` used to create both a generic `Income` "Capital Gain" row AND a separate `RedemptionService` STCG/LTCG record for every MF sell — a double-count risk. Fixed: for `.MF` symbols, `RedemptionService.recordRedemption` is now the sole record; the generic Income row is skipped when that succeeds.
7. **Real XIRR**: `XirrCalculator` (Newton-Raphson solver, `backend/.../portfolio/util/XirrCalculator.java`) computes XIRR from each holding's actual transaction cash flows (BUY negated, SELL positive, plus a final "sold today at current value" flow) — replacing a previously untrusted manually-entered/imported field. Falls back to the stored field only if there's no transaction history at all.
8. **Gmail dedup — reassessed, not rebuilt**: originally flagged as "weak" (message-ID only). On closer audit, `ParsedEmailImporter`'s content-keyed checks (`isDuplicateTrade`/`isDuplicateIncome`/`isDuplicateExpense`, bank+amount+date for FD/RD) already catch duplicates independent of Gmail message ID — this is the real safety net. Decision: documented this via a code comment rather than building a redundant fingerprint/enum system. **If revisiting**: the outer `ProcessedEmail(user_id, gmail_message_id)` uniqueness is still single-key and `type`/`status` are still free-form Strings — a real gap if ever relied upon alone, but not currently load-bearing.

## Completed Work (this session — 5-phase rework, all phases A–E complete)

**Phase A — Single source of truth**: Added `WealthController`/`/api/wealth/summary`; rewrote `NetWorthService.record()`/`NetWorthController.snapshot()` to compute server-side; replaced 5 independent frontend net-worth/allocation calculations (`Tab7RiskMatrix.tsx` ×3 sites, `Tab8MyWealth.tsx`, `DashboardPage.tsx`) with calls to `wealthApi.getSummary()`.

**Phase B — Ledger as source of truth**: Added `PortfolioService.recomputeFromLedger(Holding)`; rewired `addHolding`/`sellHolding`/`mergeHoldingsCore`/`updateHolding` to use it instead of inline weighted-average math; `updateHolding` now creates synthetic correction transactions instead of overwriting fields; added `reconcileAllUsers()` + nightly `HoldingReconciliationScheduler`.

**Phase C — FD/RD lifecycle**: `addFd`/`addRd` now both call renewal-detection on every save (not just Gmail import); added full RD lifecycle (status/closedDate/maturityAmount/renewedToId/renewedFromId, `detectAndLinkRdRenewal`, `closeRd`); added `markMaturedDeposits()` + nightly `DepositMaturityScheduler`; frontend `RDSection.tsx` rewritten to mirror `FDSection.tsx`'s renewal/closed/matured badges and close-flow UI.

**Phase D — Reconciliation layer**: Fixed MF-sell double-booking in `sellHolding`; added `ReconciliationIssue`/`ReconciliationReportDto`/`ReconciliationService`/`ReconciliationController`; added `TrackingService.checkFdRdIntegrity()`; added `ReconciliationBanner` on `DashboardPage.tsx`.

**Phase E — Gmail hardening + real XIRR**: Added `XirrCalculator` (Newton-Raphson) + 5 passing unit tests; wired into `PortfolioService.getPortfolioSummary()` via `computeRealXirr(Holding)`; documented (didn't rebuild) Gmail dedup after finding existing content-level checks already sufficient.

All phases verified via: `mvn -o -q compile` (clean), `mvn -o -q test` (full suite green, 0 errors), `npx tsc --noEmit -p tsconfig.app.json` (clean), and an actual backend restart confirming `"Started MarketAiApplication"` + port 8080 listening.

## Current Implementation Status

- Backend: compiles clean, full test suite passes, was last confirmed running (PID varies per restart) on port 8080 with all Phase A–E code live.
- Frontend: typechecks clean. **Not browser-verified this session** — the sandbox couldn't launch a dev server outside its project root. A fresh session rooted at the actual project directory should run `npm run dev` and click through Dashboard / My Wealth / Stocks / Mutual Funds / Risk Matrix to visually confirm all net-worth/allocation numbers now agree.

## Pending TODOs

1. **Live UI verification** (blocked in this session, doable in a fresh one): confirm Dashboard, My Wealth, Stocks, MF tabs show identical net worth/allocation/P&L now that Phase A is wired through.
2. **Re-confirm scope with user** on 2 of the original 10 requirements that were marked "already exist from a prior session" and NOT reworked this round:
   - "Daily Investment Decision Page" (`Tab16TodaysActions.tsx`) — exists; user has not explicitly re-confirmed it satisfies their intent post-rework.
   - "MF Profit Booking + Reinvestment chunking" (`RedemptionService`) — exists at a basic level; chunked reinvestment triggers were flagged as a "later, lower-priority phase" in the plan, not yet built.
3. **Gmail dedup outer layer**: `ProcessedEmail(user_id, gmail_message_id)` is still single-key with free-form String `type`/`status` fields — flagged as a real but currently-non-load-bearing gap (see decision #8 above). Revisit if any future bug traces back to it.
4. Broader Gmail-ingestion audit only covered dedup — the original 10-point spec's full "Email → Attachment → Password → PDF → Transaction → Validation → Database → Portfolio" pipeline across all listed sources (m.Stock/NSE/SBI MF/ICICI MF/HDFC MF/CAMS/KFintech/Banks/credit cards/UPI) was not independently re-audited this session beyond the dedup question — worth a dedicated pass if the user wants full coverage confirmation.

## Known Bugs/Issues

- None outstanding from this session's work (all test failures encountered were fixed — see below). No new bugs identified beyond the pending-scope items above.

## Important Commands

Backend compile/test:
```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home mvn -o -q -f backend/pom.xml compile
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home mvn -o -q -f backend/pom.xml test
```

Frontend typecheck (the only working invocation in this repo):
```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Backend run/restart:
```bash
pgrep -f 'com.marketai.MarketAiApplication' | xargs -I{} kill {}
lsof -i :8080 -sTCP:LISTEN | grep -v WARNING   # confirm port free
cd backend && JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home nohup mvn -o -q spring-boot:run > /tmp/backend.log 2>&1 &
grep -a "Started MarketAiApplication" /tmp/backend.log   # confirm started (may need a re-check after a few seconds — log flush race observed)
```

Frontend dev server (run from a session rooted at the actual project directory, or plain terminal):
```bash
cd frontend && npm run dev
```

## What Should NOT Be Changed Without Care

- **`PortfolioContextService.build()`** — the canonical net-worth/allocation/P&L calculator. Any new screen or endpoint needing these numbers must call through to this (or `/api/wealth/summary`), never recompute independently. Reintroducing a parallel calculation anywhere is a direct regression of Phase A.
- **`recomputeFromLedger`** — the only path that should ever set `Holding.quantity`/`averageCost`. Do not add new direct field writes to these; if a manual correction is ever needed again, use the synthetic-transaction pattern from `updateHolding`/`recordManualCorrection`.
- **FD/RD status state machine** (`ACTIVE`/`MATURED`/`CLOSED`/`MATURED_RENEWED`) — `getSummary()`'s asset totals depend on filtering out `CLOSED`/`MATURED_RENEWED`. Any new FD/RD query path must apply the same filter or it will double-count.
- **`XirrCalculator`** — pure static utility, no side effects; safe to reuse elsewhere, but don't bypass it by reintroducing a manually-set XIRR display path for holdings that have transaction history.
- Java 8 compatibility constraint: no `List.of()`, no `var`, etc. — this bit us once already (`ReconciliationService.java` compile error, fixed by switching to `Collections.emptyList()`/`singletonList()`).
- The user's explicit standing constraint for all future work in this codebase: **do not fabricate, estimate, duplicate, silently drop, or overwrite financial records.**
