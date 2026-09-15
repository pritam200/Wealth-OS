# API Specification

Base URL: `http://localhost:8080` (dev). Interactive docs at `/swagger-ui.html` when running.

**Auth**: every endpoint except the explicit allowlist below requires `Authorization: Bearer
<accessToken>`. The user is resolved via `@AuthenticationPrincipal User` — no endpoint accepts a
`userId` path/query parameter, so one user cannot read another's data by changing an ID.

**Unauthenticated (permitAll) paths**: `/api/auth/**`, `/api/gmail/callback`, `/api/gmail/push`
(protected instead by a shared-secret `?token=` query param), `/api-docs/**`, `/swagger-ui/**`,
`/actuator/health`.

**Note**: several GET endpoints (global reference data — `amfi`, `analyst`, `card.catalog/
categories`, `forecast`, `market`, `news`, `recommendation.benchmarks`, `signal`, `technical`) do
not take a `User` parameter but are still gated by the default `anyRequest().authenticated()`
rule — they rely entirely on the filter chain, not on defensive code in the controller.

---

## Auth — `/api/auth`

| Method | Path | Body | Auth |
|---|---|---|---|
| POST | `/register` | `RegisterRequest` | Unauthenticated |
| POST | `/login` | `LoginRequest` → `AuthResponse` | Unauthenticated |
| POST | `/refresh` | `RefreshTokenRequest` | Unauthenticated |
| POST | `/logout` | — | Technically requires a valid JWT (`@AuthenticationPrincipal UserDetails`) despite the path being allowlisted |

## AI — `/api/ai`

| Method | Path | Body | Return |
|---|---|---|---|
| POST | `/analyse-stock` | `AiRequest` | `AiResponse` |
| POST | `/market-summary` | — | `AiResponse` |
| POST | `/portfolio-review/{portfolioId}` | — | `AiResponse` |
| POST | `/chat` | `AiRequest` | `AiResponse` |

## AI Review Queue — `/api/review`

| Method | Path | Params/Body | Return |
|---|---|---|---|
| GET | `/` | `all=false` | `List<EmailReviewItem>` |
| GET | `/count` | — | `{pending: number}` |
| POST | `/{id}/decision` | `ReviewDecisionRequest` (ACCEPT/EDIT/REJECT) | `EmailReviewItem` |

## AMFI — `/api/mf-nav`

| Method | Path | Params | Return |
|---|---|---|---|
| GET | `/` | `name` | `AmfiNavResult` or 404 |

## Analyst — `/api/analyst`

| Method | Path | Path Vars | Params | Return |
|---|---|---|---|---|
| GET | `/{symbol}` | `symbol` | `name` (opt) | `AnalystAssessment` |

## Actions — `/api/actions`, `/api/todays-actions`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/api/actions` | — | — | `List<ActionItemDto>` |
| POST | `/api/actions` | — | `ActionUpdateRequest` | `ActionItemDto` |
| PATCH | `/api/actions/{id}/note` | `id` | `{note}` | `ActionItemDto` |
| GET | `/api/todays-actions` | — | — | `TodaysActionsResponse` |

## Cards — `/api/cards`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/catalog` | — | — | `List<CatalogEntry>` |
| GET | `/categories` | — | — | `List<String>` |
| GET | `/` | — | — | `List<CardResponse>` |
| POST | `/` | — | `CardRequest` | `CardResponse` |
| PUT | `/{id}` | `id` | `CardRequest` | `CardResponse` |
| DELETE | `/{id}` | `id` | — | 204 |
| PUT | `/{id}/points` | `id` | `{pointsBalance}` | `CardResponse` |
| POST | `/recommend` | — | `RecommendRequest` | `List<RecommendResult>` |
| GET | `/points-tips` | — | — | `List<PointsTip>` |

## Expenses — `/api/expenses`

| Method | Path | Params | Body | Return |
|---|---|---|---|---|
| GET | `/` | `year, month` | — | `List<ExpenseResponse>` |
| POST | `/` | — | `ExpenseRequest` | `ExpenseResponse` (201) |
| PUT | `/{id}` | — | `ExpenseRequest` | `ExpenseResponse` |
| DELETE | `/{id}` | — | — | 204 |
| GET | `/miscategorized-investments` | — | — | `List<ExpenseResponse>` |
| DELETE | `/miscategorized-investments` | — | — | `{removed: number}` |
| GET | `/summary` | `year, month` | — | `Map<String,Object>` |

## Forecast — `/api/forecast`

| Method | Path | Params | Return |
|---|---|---|---|
| GET | `/indices` | — | `Map<String,String>` |
| GET | `/` | `symbol, horizon=2W, name` | `ForecastResponse` |

## Gmail — `/api/gmail`

| Method | Path | Path Vars | Params/Body | Return |
|---|---|---|---|---|
| GET | `/auth-url` | — | — | `{url}` |
| GET | `/callback` | — | `code, state` | redirects (allowlisted) |
| GET | `/status` | — | — | `GmailStatusResponse` |
| POST | `/sync` | — | — | `GmailSyncResult` (inline/blocking — prefer `/api/sync/gmail`) |
| GET | `/history` | — | `limit=100` | `List<ProcessedEmail>` |
| GET | `/pending-pdfs` | — | — | `List<PendingPdf>` |
| POST | `/pending-pdfs/{id}/unlock` | `id` | `{password}` | `Map<String,Object>` |
| DELETE | `/pending-pdfs/{id}` | `id` | — | 204 |
| GET | `/reconciliation-report` | — | — | `ReconciliationReportDto` |
| GET | `/contract-note-debug` | — | — | `List<PendingPdf>` |
| POST | `/reprocess-failed` | — | — | `Map<String,Object>` |
| GET | `/debug-pdf/{id}` | `id` | — | `Map<String,Object>` |
| GET | `/excluded-senders` | — | — | `List<ExcludedSender>` |
| POST | `/excluded-senders` | — | `{pattern, label}` | `ExcludedSender` |
| DELETE | `/excluded-senders/{id}` | `id` | — | 204/404 |
| GET | `/saved-passwords` | — | — | `List<SavedPasswordResponse>` |
| PUT | `/saved-passwords/{id}` | `id` | `{password}` | 204 |
| DELETE | `/saved-passwords/{id}` | `id` | — | 204 |
| POST | `/resync` | — | — | `GmailSyncResult` (wipes ProcessedEmail + fingerprints for the user) |
| POST | `/retry-failed` | — | — | `GmailSyncResult` |
| DELETE | `/disconnect` | — | — | 204 |
| POST | `/push` | — | `?token=`, Pub/Sub body | 200 always (**unauthenticated**, shared-secret token) |

## Goals — `/api/goals`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/` | — | — | `List<GoalResponse>` |
| POST | `/` | — | `GoalRequest` | `GoalResponse` |
| PUT | `/{id}` | `id` | `GoalRequest` | `GoalResponse` |
| DELETE | `/{id}` | `id` | — | 204 |

## Income — `/api/income`

| Method | Path | Params | Body | Return |
|---|---|---|---|---|
| GET | `/` | `year, month` | — | `List<IncomeResponse>` |
| GET | `/by-source` | `source, year` | — | `List<IncomeResponse>` |
| POST | `/` | — | `IncomeRequest` | `IncomeResponse` |
| PUT | `/{id}` | — | `IncomeRequest` | `IncomeResponse` |
| DELETE | `/{id}` | — | — | 204 |
| GET | `/summary` | `year, month` | — | `Map<String,Object>` |

## Ledger — `/api/ledger`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/accounts` | — | — | `List<CashAccount>` |
| POST | `/accounts` | — | `CashAccountRequest` | `CashAccount` |
| PUT | `/accounts/{id}/balance` | `id` | `{balance}` | `CashAccount` |
| GET | `/transfers` | — | — | `List<LedgerTransfer>` |
| POST | `/transfers` | — | `TransferRequest` | `LedgerTransfer` |
| DELETE | `/transfers/{id}` | `id` | — | 204 |
| GET | `/cash-total` | — | — | `{totalCash}` |

## Market — `/api/market`

| Method | Path | Path Vars | Params | Return |
|---|---|---|---|---|
| GET | `/overview` | — | — | `MarketOverviewDto` |
| GET | `/quote/{symbol}` | `symbol` | — | `QuoteDto` |
| GET | `/search` | — | `q` | `List<Stock>` |
| GET | `/history/{symbol}` | `symbol` | `from, to` (ISO date, required) | `List<PriceHistory>` |
| POST | `/history/{symbol}/fetch` | `symbol` | `range=1y` | 202 |

## Net Worth — `/api/networth`

| Method | Path | Return |
|---|---|---|
| GET | `/series` | `List<NetWorthSnapshot>` |
| POST | `/snapshot` | `NetWorthSnapshot` |

## News — `/api/news`

| Method | Path | Path Vars | Params | Return |
|---|---|---|---|---|
| GET | `/` | — | `page=0, size=20` (capped 50) | `Page<News>` |
| GET | `/symbol/{symbol}` | `symbol` | — | `List<News>` |

## Portfolio — `/api/portfolios`

| Method | Path | Path Vars | Params/Body | Return |
|---|---|---|---|---|
| POST | `/` | — | `{name, description}` | `Portfolio` (201) |
| GET | `/` | — | — | `List<Portfolio>` |
| GET | `/{id}/summary` | `id` | — | `PortfolioSummaryDto` |
| POST | `/{id}/holdings` | `id` | `AddHoldingRequest` | `Holding` (201) |
| DELETE | `/{portfolioId}/holdings/{holdingId}` | both | — | 204 |
| DELETE | `/{portfolioId}/holdings` | `portfolioId` | `type=all\|stocks\|mf` | `Map<String,Integer>` |
| PUT | `/{portfolioId}/holdings/{holdingId}` | both | `{quantity, averageCost, currentPrice, investedAmount, broker, folio, buyDate, xirr}` | `Holding` |
| POST | `/recalculate` | — | — | `Map<String,String>` |
| GET | `/integrity-check` | — | — | `IntegrityReportDto` |
| POST | `/merge-duplicate-symbols` | — | — | `MergeSummaryDto` |
| POST | `/fix-mismatched-tickers` | — | — | `MergeSummaryDto` |
| POST | `/rebuild` | — | — | `Map<String,Object>` |
| POST | `/{portfolioId}/holdings/{holdingId}/sell` | both | `{quantity, salePrice}` | 204 |
| GET | `/{portfolioId}/holdings/{holdingId}/transactions` | both | — | `List<TransactionDto>` |
| GET | `/mf-transactions` | — | `days=7` (clamped 1–365) | `List<TransactionDto>` |

## Recommendation / Wealth — `/api/recommendation`, `/api/wealth`

| Method | Path | Path Vars | Params/Body | Return |
|---|---|---|---|---|
| GET | `/api/recommendation/benchmarks` | — | — | `Map<String,Double>` |
| GET | `/api/recommendation/{symbol}` | `symbol` | `name, pnlPercent, holdingValue, totalPortfolioValue` (opt) | `AnalystAssessment` |
| POST | `/api/recommendation/mf` | — | `MfRecommendationRequest` | `AnalystAssessment` |
| GET | `/api/wealth/summary` | — | — | `PortfolioContext` (single source of truth for net worth) |

## Reconciliation — `/api/reconciliation`

| Method | Path | Return |
|---|---|---|
| GET | `/report` | `ReconciliationReportDto` |

Distinct from `/api/gmail/reconciliation-report` (import-history-based) — this is a broader
cross-domain check (`ReconciliationService.checkAll`).

## Redemptions — `/api/redemptions`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/` | — | — | `List<MfRedemption>` |
| GET | `/{id}/deployment-plan` | `id` | — | `DeploymentPlan` |
| POST | `/{id}/reinvestments` | `id` | `ReinvestmentRequest` | `MfRedemption` |

## Reminders — `/api/reminders`

| Method | Path | Return |
|---|---|---|
| GET | `/` | `List<ReminderResponse>` |

`[PARTIALLY IMPLEMENTED]` — read-only; no create/update/delete endpoint exists.

## Recurring Investments — `/api/recurring-investments`

| Method | Path | Path Vars | Body | Return |
|---|---|---|---|---|
| GET | `/` | — | — | `List<RecurringInvestmentResponse>` |
| POST | `/` | — | `RecurringInvestmentRequest` | `RecurringInvestmentResponse` (201) |
| DELETE | `/{id}` | `id` | — | 204 |

## Signals — `/api/signals`

| Method | Path | Path Vars | Return |
|---|---|---|---|
| GET | `/{symbol}` | `symbol` | `SignalPayload` |

## Sync Jobs — `/api/sync`

| Method | Path | Path Vars | Params | Return |
|---|---|---|---|---|
| POST | `/gmail` | — | `full=false, lookback` | `SyncJobDto` (202) — **prefer this over `/api/gmail/sync`** |
| GET | `/jobs/{id}` | `id` | — | `SyncJobDto` or 404 |
| GET | `/jobs` | — | `limit=10` (capped 50) | `List<SyncJobDto>` |

## Tax — `/api/tax`

| Method | Path | Params | Return |
|---|---|---|---|
| GET | `/summary` | `fyStartYear` (opt) | `TaxResponse` |

## Technical — `/api/technical`

| Method | Path | Path Vars | Return |
|---|---|---|---|
| GET | `/{symbol}` | `symbol` | `TechnicalAnalysisDto` |

## Tracking (FD/RD/Loan/EPF/Other) — `/api/tracking`

| Method | Path | Body | Return |
|---|---|---|---|
| GET | `/summary` | — | `TrackingSummaryResponse` |
| GET/POST/PUT/DELETE | `/fd`, `/fd/{id}` | `FdRequest` | `FdResponse` |
| POST | `/fd/{id}/close` | `{actualAmount}` (opt) | `FdResponse` |
| GET/POST/PUT/DELETE | `/rd`, `/rd/{id}` | `RdRequest` | `RdResponse` |
| POST | `/rd/{id}/close` | `{}` (opt) | `RdResponse` |
| GET/POST/PUT/DELETE | `/loan`, `/loan/{id}` | `LoanRequest` | `LoanResponse` |
| GET/POST/PUT/DELETE | `/epf`, `/epf/{id}` | `EpfRequest` (**no `@Valid`** — inconsistent with FD/RD/Loan/Other) | `EpfResponse` |
| GET/POST/PUT/DELETE | `/other`, `/other/{id}` | `OtherAssetRequest` | `OtherAssetResponse` |

---

## Reserved but unimplemented

`/api/admin/**` — configured in `SecurityConfig` as `hasRole("ADMIN")`, but **no controller maps
under this path**. `[PARTIALLY IMPLEMENTED — dead security rule]`.
