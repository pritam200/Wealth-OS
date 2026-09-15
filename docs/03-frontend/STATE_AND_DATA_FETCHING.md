# State & Data Fetching

## Server-state library: none

**Confirmed by repo-wide search**: zero matches for `useQuery`, `useSWR`, `react-query`, or
`@tanstack` across `src/` and `package.json`. `[PLANNED / NOT IMPLEMENTED]` — every data-fetching
component uses raw `useState` + `useEffect` + direct `axios` calls. Manual loading/error state,
manual polling via `setInterval`/`setTimeout` (e.g. `LiveTicker` in `App.tsx` polls every 60s;
`useSyncJob` hook polls every 2s, capped at 30 minutes).

**Implication for new code**: there is no cache, no automatic refetch-on-focus, no request
dedup. If you add a new data-driven component, follow the existing pattern (local `useState` +
`useEffect`) rather than introducing a server-state library in isolation — a mixed pattern would
be more confusing than the current consistent-but-manual approach.

## Client state — Zustand (2 stores total)

| Store | File | State | Actions | Persisted? |
|---|---|---|---|---|
| `authStore` | `src/store/authStore.ts` (40 lines) | `user` (userId/name/email/roles), `isAuthenticated` | `login(response)`, `logout()` | Yes, localStorage key `auth-store` |
| `privacyStore` | `src/store/privacyStore.ts` (20 lines) | `masked` (default `true`) | `toggle()` | Yes, localStorage key `privacy-store` |

`privacyStore.masked` defaults to `true` on every fresh browser/device — "so no one sees real
numbers without deliberately revealing them" (source comment).

## API layer — `src/api/` (23 files, 1,243 lines total)

Every file builds on the shared `apiClient` (see `ROUTING_AND_AUTH.md` for the interceptor
setup). Full method reference:

| File | Export | Key methods |
|---|---|---|
| `actions.ts` | `actionsApi` | `today()`, `record(update)`, `note(id, note)` |
| `ai.ts` | `aiApi` | `analyseStock`, `marketSummary`, `portfolioReview`, `chat` |
| `amfiNav.ts` | `amfiNavApi` | `lookup(name)` |
| `analyst.ts` | `analystApi`, `recommendationApi`, `recommendationMfApi`, `benchmarksApi` | `assess`, `get`, `get(mf params)`, `get()` |
| `auth.ts` | `authApi` | `register`, `login`, `logout` |
| `card.ts` | `cardApi` | `catalog`, `categories`, `list`, `add`, `update`, `delete`, `updatePoints`, `recommend`, `pointsTips` |
| `client.ts` | `apiClient` | the axios instance itself (no domain methods) |
| `expense.ts` | `expenseApi` | `list`, `add`, `update`, `delete`, `summary`, `listMiscategorizedInvestments`, `purgeMiscategorizedInvestments` |
| `forecast.ts` | `forecastApi` | `indices`, `get` |
| `gmail.ts` | `gmailApi`, `syncJobApi` | 19 gmail methods + `queueGmail`, `get(jobId)`, `recent` |
| `income.ts` | `incomeApi` | `list`, `add`, `update`, `delete`, `summary`, `bySource` |
| `ledger.ts` | `ledgerApi` | `accounts`, `addAccount`, `setBalance`, `transfers`, `transfer`, `deleteTransfer`, `cashTotal` |
| `market.ts` | `marketApi` | `getOverview`, `getQuote`, `search`, `getHistory`, `getTechnicals`, `getNews`, `getStockNews` |
| `planning.ts` | `reminderApi`, `goalApi`, `netWorthApi`, `taxApi` | `list`, goal CRUD, `series`/`snapshot`, `summary` |
| `portfolio.ts` | `portfolioApi` | `list`, `create`, `getSummary`, `addHolding`, `removeHolding`, `sellHolding`, `updateHolding`, `recalculate`, `rebuild`, `getTransactions`, `getRecentMfTransactions`, `integrityCheck`, `mergeDuplicateSymbols` |
| `reconciliation.ts` | `reconciliationApi` | `getReport` |
| `redemption.ts` | `redemptionApi` | `list`, `getDeploymentPlan`, `recordReinvestment` |
| `review.ts` | `reviewApi` | `list`, `count`, `decide` |
| `scheduledInvestment.ts` | `scheduledInvestmentApi` | `list`, `add`, `delete` |
| `technical.ts` | `technicalApi` | `analyse` |
| `todaysActions.ts` | `todaysActionsApi` | `get` |
| `tracking.ts` | `trackingApi` | `getSummary` + full CRUD for FD/RD/Loan/Other/EPF (5 methods × 5 domains) |
| `wealth.ts` | `wealthApi` | `getSummary()` — **the single documented source of truth for net worth/allocation figures across all tabs** |

## Privacy masking — `Amount`/`useMaskedText`

`src/components/shared/Amount.tsx` (18 lines) + `src/store/privacyStore.ts`:

- `Amount({ value, className })` — reads `masked` from `privacyStore`, renders either the literal
  `'••••••'` or the raw `value`.
- `useMaskedText()` — returns a formatter `(value) => masked ? '••••••' : String(value)` for
  call sites that can't use `<Amount>` directly (chart tooltip formatters, SVG `<text>` nodes,
  `title` attributes) — used pervasively across nearly every tab.
- **Enforcement is by convention, not by the type system.** Nothing stops a new component from
  rendering `value` directly and bypassing the mask. When adding a component that shows a rupee
  figure, route it through one of these two — do not render a raw currency value.
- Only controls wealth-figure visibility — no masking of names/emails/other PII.
