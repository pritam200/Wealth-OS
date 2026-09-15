# Frontend Structure

## App shell & routing (`src/App.tsx`, 255 lines)

### Real react-router routes

| Path | Element | Guard |
|---|---|---|
| `/login` | `<LoginPage />` | none |
| `/register` | `<RegisterPage />` | none |
| `/stock/:symbol` | `<StockPage />` (with a back-button wrapper) | `PrivateRoute` |
| `/*` (catch-all) | `<AppShell />` | `PrivateRoute` |

**Everything else is client-side tab state, not routing.** `AppShell` holds
`useState<number>(1)` (default tab = Market Trends) and passes it to `Sidebar`/`TabContent`.
Switching tabs never touches the URL or browser history.

### `NAV_SECTIONS` → tab ID map

| Section | Tab ID(s) → label |
|---|---|
| Dashboard | 0 → Dashboard |
| My Wealth | 8 → Portfolio & Assets, 7 → Net Worth & Risk |
| Stocks | 13 → Holdings & Signals, 2 → Stock Insights, 4 → Price Projections |
| Mutual Funds | 6 → Holdings & Signals |
| Markets | 1 → Market Trends, 3 → Market Forecast, 5 → News & Catalysts |
| Income & Expenses | 10 |
| Dividends | 14 |
| Financial Planning | 12 |
| Cards & Rewards | 11 |
| AI Advisor | 9 → Daily Actions, 16 → Today's Investment Actions |
| Data Sync | 15 |

### `TabContent` dispatcher (tab ID → component)

```
0→DashboardPage  1→Tab1MarketTrends  2→Tab2StockAnalysis  3→Tab3MarketForecast
4→Tab4StockProjections  5→Tab5NewsAndCatalysts  6→Tab6MutualFunds  7→Tab7RiskMatrix
8→Tab8MyWealth  9→Tab9AiAdvisor  10→Tab10Expenses  11→Tab11Cards  12→Tab12Planning
13→StocksHoldingsSignals (exported from Tab7RiskMatrix.tsx — NOT its own file)
14→Tab14Dividends  15→DataSyncPage (from src/pages/, not src/pages/tabs/)  16→Tab16TodaysActions
default→Tab1MarketTrends
```

`sectionOf(tabId)` finds which nav section contains a tab (drives the Topbar's section label).

## `src/pages/tabs/` — every file

| File | Lines | Renders |
|---|---|---|
| `Tab1MarketTrends.tsx` | 208 | Index ticker ribbon + trend visualization |
| `Tab2StockAnalysis.tsx` | 434 | Default watchlist + technical indicators, links to `/stock/:symbol` |
| `Tab3MarketForecast.tsx` | 266 | Symbol search + Bull/Base/Bear scenario bands, embeds `AiCopilot` |
| `Tab4StockProjections.tsx` | 219 | Per-symbol projection card (quote + technicals + AI narrative) |
| `Tab5NewsAndCatalysts.tsx` | 132 | Sentiment-tagged news feed |
| `Tab6MutualFunds.tsx` | 700 | Allocation chart, add-MF modal, transaction history, benchmarks, redemptions (largest tab file) |
| `Tab7RiskMatrix.tsx` | 618 | Net worth bar, risk score, holdings-with-signals groups — **also houses** `PortfolioSection`, `StocksHoldingsSignals`, `MfHoldingsSignals`, `NetWorthBar`, `RiskScoreCard`, consumed by other tabs |
| `Tab8MyWealth.tsx` | 110 | Composes `PortfolioSection`+`NetWorthBar` (imported from Tab7) with FD/RD/EPF/loan/cash sections |
| `Tab9AiAdvisor.tsx` | 318 | Stock/MF recommendation cards, AI portfolio review |
| `Tab10Expenses.tsx` | 82 | `ExpenseSection`, `IncomeSection`, `SavingsRatioCard`, `MerchantSpendSection` |
| `Tab11Cards.tsx` | 112 | Card list, reward wallet, `CardOptimizer`, `CardBreakEven` |
| `Tab12Planning.tsx` | 326 | Reminders, goals, tax summary, recurring investments |
| `Tab14Dividends.tsx` | 254 | Dividend income list/add form |
| `Tab16TodaysActions.tsx` | 498 | Buy/sell-reduce/book-profit/hold/watch buckets with execute/snooze/note |

Total 4,277 lines across 14 files (tab IDs 0 and 15 render from `src/pages/` instead).

**Cross-tab coupling**: `Tab8MyWealth.tsx` imports `PortfolioSection`/`NetWorthBar` directly from
`Tab7RiskMatrix.tsx` — tab files are not fully independent modules.

## Design system

### Color tokens (`tailwind.config.js`) — light theme, `darkMode: 'class'`

| Token | Value | Note |
|---|---|---|
| `surface.DEFAULT` | `#F6F8FB` | page background |
| `surface.card`/`.panel` | `#FFFFFF` | |
| `surface.border` | `#E3E8EF` | |
| `surface.hover`/`.muted` | `#F1F5F9` | |
| `ink` | `#0F172A` | primary text |
| `brand.DEFAULT` | `#2563EB` | |
| `brand.light` | `#1D4ED8` | deliberately *darker* than DEFAULT — more contrast for text/icons on white |
| `bull` | `#047857` | emerald, positive/buy |
| `bear` | `#DC2626` | rose, negative/sell |
| `neutral`/`gold` | `#B45309` | amber, warning/watch |
| `accent` | `#2563EB` | |

**Gray scale is emphasis-ordered, not lightness-ordered** — inverted from typical Tailwind so a
future dark→light flip needs no component changes:

| Token | Hex | Role |
|---|---|---|
| `gray.100` | `#0F172A` | highest emphasis (darkest) |
| `gray.500` | `#5B6B7F` | muted body/caption (5.45:1 contrast) |
| `gray.700` | `#687485` | faintest labels (4.75:1) |
| `gray.800` | `#E3E8EF` | borders |
| `gray.900` | `#F1F5F9` | subtle fills (lightest) |

**Parallel chart-color source**: `src/theme/chartTheme.ts` — Recharts needs raw hex, not
Tailwind classes, so this file duplicates the semantic colors and must be kept in sync with
`tailwind.config.js` manually. Exports `CHART`, `CHART_SERIES` (12-color categorical palette),
`tooltipStyle`/`axisProps`/`gridProps`, and `CATEGORY_COLORS` for income/expense coloring.

### `@layer components` classes (`src/index.css`)

| Class family | Purpose |
|---|---|
| `.card`, `.card-elevated`, `.card-flat`, `.panel` | Surface primitives — flat white panels, no gradient/blur, hairline borders |
| `.icon-badge`, `.icon-badge-brand/-bull/-bear/-neutral/-gold`, `-sm` | Tinted icon containers |
| `.kpi-tile` | Icon+label/value row |
| `.btn-primary`, `.btn-secondary`, `.btn-ghost`, `.btn-icon` | Button family |
| `.pill`, `.pill-bull/-bear/-neutral/-info/-muted` | Status pills (non-numeric state) |
| `.badge-bull/-bear/-neutral/-gold` | Monospaced value badges |
| `.stat-label`, `.stat-value`, `.stat-value-gradient`, `.stat-value-hero` | Typography scale for figures — `.stat-value-gradient` is explicitly solid `text-ink`, not gradient-clipped (contrast reasons on thin mono strokes) |
| `.num`, `.value-bull/-bear/-neutral` | Inline mono numeric utilities |
| `.input-field` | Form input |
| `.data-table` (+ nested rules) | Table with hover accent bar |
| `.divider`, `.tick-up`/`.tick-down` | Misc |

## Component hierarchy by domain

### `src/components/wealth/` (19 files)
`CardBreakEven`, `CardOptimizer`, `CashAccountsSection`, `ChunkRebalancingTracker`,
`CreditCardSection` (largest, ~30KB), `DividendSection`, `EpfSection`, `ExpenseSection`,
`FDSection`, `HoldingTrendBadge`, `IncomeSection`, `LoanSection`, `MerchantSpendSection`,
`MfTrendBadge`, `NetWorthTrend`, `OtherAssetsSection`, `RDSection`, `RedeemedInvestments`,
`WealthCalculator`.

### `src/components/market/` (3 files)
`AnalystPanel` (full BUY/HOLD/SELL card), `StockChart` (hand-drawn SVG 6-month chart + narrative
summary), `StockSearch`.

### `src/components/portfolio/` (2 files)
`AllocationChart` (Recharts pie, buckets long tails into "Others" beyond 8 slices),
`PortfolioTable`.

### `src/components/ai/` (1 file)
`AiCopilot` — chat widget hitting `aiApi.chat`.

### `src/components/layout/` (2 files)
`Sidebar` (renders `NAV_SECTIONS`), `Topbar` (search, market-open indicator, hide/show-wealth
toggle, logout).

### `src/components/shared/` (6 files — the primitives layer)
`Amount`/`useMaskedText` (privacy masking), `AssetAllocationRing`, `IndexTicker`, `SignalBadge`,
`StatTile`, `TransactionDetail`.

### Other (not domain-scoped)
`BulkImportModal`, `GmailConnect` (largest non-tab component — OAuth, sync history, pending-PDF
unlock, excluded senders, saved passwords), `ReviewQueue`, `SyncNowPanel` (built on the
`useSyncJob` hook), `dashboard/IndexCards`, `dashboard/MarketOverviewBar`.

## Known dead/orphaned code

- **`src/pages/PortfolioPage.tsx`** (279 lines) — confirmed never imported anywhere. Not
  reachable via any route or tab.
- **`MfHoldingsSignals`** — exported from `Tab7RiskMatrix.tsx` alongside `StocksHoldingsSignals`,
  but never wired into `TabContent`.
- **`src/pages/{auth}`** — a stray empty directory with literal curly braces in the name
  (accidental `mkdir` artifact), alongside the real `src/pages/auth/`.
