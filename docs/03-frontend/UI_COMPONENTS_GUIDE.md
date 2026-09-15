# UI Components Guide — Page by Page

For the full component-hierarchy listing, see `FRONTEND_STRUCTURE.md`. This document walks each
tab and what it's built from.

## Tab 0 — Dashboard (`DashboardPage.tsx`, in `src/pages/`, not `tabs/`)

Composes `dashboard/IndexCards` and `dashboard/MarketOverviewBar` (both reuse
`shared/IndexTicker`). Receives `onNavigate` to jump to other tabs.

## Tab 1 — Market Trends (`Tab1MarketTrends.tsx`)

Index ticker ribbon + sector/trend visualization, sourced from `marketApi.getOverview()`.

## Tab 2 — Stock Insights (`Tab2StockAnalysis.tsx`, 434 lines — largest single-purpose tab besides MF)

Default watchlist (RELIANCE, TCS, etc.) with technical indicators per symbol; add/remove
watchlist entries; links out to `/stock/:symbol` (the one real route besides auth/catch-all).

## Tab 3 — Market Forecast (`Tab3MarketForecast.tsx`)

Symbol search + Bull/Base/Bear scenario bands across 1W/2W/4W horizons (`forecastApi`), embeds
`components/ai/AiCopilot`.

## Tab 4 — Price Projections (`Tab4StockProjections.tsx`)

Per-symbol card combining `marketApi.getQuote`, `technicalApi.analyse`, and
`market/AnalystPanel`/`market/StockChart`.

## Tab 5 — News & Catalysts (`Tab5NewsAndCatalysts.tsx`)

Sentiment-tagged news feed (Bullish/Bearish/Watch pills) from `marketApi.getNews`.

## Tab 6 — Mutual Funds (`Tab6MutualFunds.tsx`, 700 lines — the largest tab file)

Allocation chart (`portfolio/AllocationChart`), add-MF modal, transaction history modal, recent
purchases, benchmark comparisons (via `benchmarksApi`), redeemed investments
(`wealth/RedeemedInvestments`, which itself renders a deployment-plan card from
`redemptionApi.getDeploymentPlan`).

## Tab 7 — Net Worth & Risk (`Tab7RiskMatrix.tsx`, 618 lines)

Net worth bar, risk score card, stock/MF holdings-with-signals groups, wealth calculator.
**This file also exports and is the true home of**: `PortfolioSection`, `StocksHoldingsSignals`
(= Tab 13), `MfHoldingsSignals` (exported but **dead — never wired into `TabContent`**),
`NetWorthBar`, `RiskScoreCard` — all consumed by other tabs/pages. If you're looking for "where
is the net worth bar actually defined", it's here, not in `Tab8MyWealth.tsx`.

## Tab 8 — Portfolio & Assets (`Tab8MyWealth.tsx`, 110 lines)

Thin composition: imports `PortfolioSection`+`NetWorthBar` from `Tab7RiskMatrix.tsx`, adds
FD/RD/EPF/loan/other-asset/cash-account sections (`wealth/FDSection`, `RDSection`, `EpfSection`,
`LoanSection`, `OtherAssetsSection`, `CashAccountsSection`), bulk import modal
(`BulkImportModal`), net worth trend chart (`wealth/NetWorthTrend`), dividend section.

## Tab 9 — AI Advisor / Daily Actions (`Tab9AiAdvisor.tsx`)

Stock/MF recommendation cards via `recommendationApi`/`recommendationMfApi`, AI-generated
portfolio review (`aiApi.portfolioReview`).

## Tab 10 — Income & Expenses (`Tab10Expenses.tsx`, 82 lines)

Thin composition of `wealth/ExpenseSection`, `wealth/IncomeSection`,
`wealth/IncomeSection`'s `SavingsRatioCard`, `wealth/MerchantSpendSection`, plus a banner to purge
miscategorized-investment expense rows (`expenseApi.purgeMiscategorizedInvestments`).

## Tab 11 — Cards & Rewards (`Tab11Cards.tsx`)

Card list, reward wallet/points value, `wealth/CardOptimizer` (best-card recommender),
`wealth/CardBreakEven` (fee break-even calculator).

## Tab 12 — Financial Planning (`Tab12Planning.tsx`, 326 lines)

Reminders (`reminderApi.list` — read-only), financial goals (`goalApi` full CRUD), tax summary
(`taxApi.summary`), recurring investments (SIP/PPF/NPS via `scheduledInvestmentApi`).

## Tab 13 — Holdings & Signals (Stocks) — no dedicated file

Renders `StocksHoldingsSignals`, exported from `Tab7RiskMatrix.tsx`.

## Tab 14 — Dividends (`Tab14Dividends.tsx`)

Dividend income list/add form by year, sourced through `incomeApi` filtered to
`IncomeSource.DIVIDEND`.

## Tab 15 — Data Sync — no file in `tabs/`

Renders `DataSyncPage` from `src/pages/`. Built around `components/GmailConnect.tsx` (largest
non-tab component: OAuth connect/disconnect, sync history, pending-PDF unlock, excluded senders,
saved passwords, reconciliation report UI), plus `components/ReviewQueue.tsx` (human review
decisions) and `components/SyncNowPanel.tsx` (built on the `useSyncJob` hook — queues an async
sync via `POST /api/sync/gmail` and polls its status).

## Tab 16 — Today's Investment Actions (`Tab16TodaysActions.tsx`, 498 lines)

Buy/sell-reduce/book-profit/hold/watch buckets from `todaysActionsApi.get()`, with per-item
execute/snooze/note actions wired to `actionsApi`.

## Route: `/stock/:symbol` (`StockPage.tsx`)

The one non-tab, non-auth route. Full stock detail page, wrapped with a back button in `App.tsx`.

---

## Shared primitives used across most tabs

| Component | Used for |
|---|---|
| `shared/Amount` / `useMaskedText` | Every rupee figure — privacy masking |
| `shared/StatTile` | Generic icon+label+value KPI tile (tone: brand/bull/bear/neutral/gold) |
| `shared/SignalBadge` | BUY/SELL/HOLD colored badge with strength |
| `shared/IndexTicker` | Index quote chip, reused by Dashboard and Tab1 |
| `shared/AssetAllocationRing` | Recharts donut for equity/debt/other split |
| `shared/TransactionDetail` | Labeled field list modal for a single transaction |
