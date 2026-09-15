# Wealth-OS — API Reference

Base URL: `http://localhost:8080` (override with `VITE_API_URL` on the frontend).

**Auth:** every endpoint except `/api/auth/**` and `/api/gmail/callback` requires
`Authorization: Bearer <accessToken>`. The user is resolved from the token via
`@AuthenticationPrincipal` — no endpoint takes a `userId` parameter, so one user can never read
another's data by changing a path.

Interactive docs (when the backend is running): `http://localhost:8080/swagger-ui.html`

---

## Auth — `/api/auth`

| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/register` | `{email, password, name}` | |
| POST | `/login` | `{email, password}` | → `{accessToken, refreshToken}` |
| POST | `/refresh` | `{refreshToken}` | |
| POST | `/logout` | `{refreshToken}` | revokes the refresh token |

---

## Wealth summary — `/api/wealth`

| Method | Path | Notes |
|---|---|---|
| GET | `/summary` | **The single source of truth for net worth.** Every screen showing net worth, total assets or allocation must use this. |

Returns `PortfolioContext`:

```json
{
  "stocksValue": 0, "mfValue": 0, "fdValue": 0, "rdValue": 0, "epfValue": 0,
  "otherAssetsValue": 0, "cashValue": 0, "loansOutstanding": 0,
  "totalAssets": 0, "netWorth": 0,
  "equityPercent": null, "debtPercent": null, "otherPercent": null,
  "equityInvested": 0, "equityCurrent": 0, "equityPnl": 0, "equityPnlPercent": null,
  "stockCount": 0, "mfCount": 0,
  "topStockExposures": [], "sectorExposures": [], "concentrationFlags": [],
  "dataGaps": []
}
```

Percentages are `null` — never `0` — when undefined (e.g. no assets), so "unknown" is never
displayed as "0%".

---

## Portfolio — `/api/portfolios`

| Method | Path | Notes |
|---|---|---|
| GET | `/` | list portfolios |
| POST | `/` | create |
| GET | `/{id}/summary` | holdings + P&L + **real XIRR** computed from the ledger |
| POST | `/{id}/holdings` | add holding (creates a BUY transaction) |
| PUT | `/{portfolioId}/holdings/{holdingId}` | edit — writes **synthetic correction transactions**, never direct field overwrites |
| DELETE | `/{portfolioId}/holdings/{holdingId}` | |
| DELETE | `/{portfolioId}/holdings` | bulk delete |
| POST | `/{portfolioId}/holdings/{holdingId}/sell` | records a SELL; for `.MF` symbols creates the `MfRedemption` record of truth |
| GET | `/{portfolioId}/holdings/{holdingId}/transactions` | the ledger for one holding |
| GET | `/mf-transactions` | all MF transactions |
| POST | `/recalculate` | refresh current prices |
| POST | `/rebuild` | replay every ledger → holdings |
| GET | `/integrity-check` | mismatched tickers, duplicate symbols |
| POST | `/merge-duplicate-symbols` | |
| POST | `/fix-mismatched-tickers` | |

---

## Ledger: cash & transfers — `/api/ledger`

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/accounts` | | active cash accounts |
| POST | `/accounts` | `{name, bank?, lastFour?, accountType?, balance?, asOf?}` | |
| PUT | `/accounts/{id}/balance` | `{balance}` | explicit statement reconciliation — balances are never inferred from email |
| GET | `/transfers` | | |
| POST | `/transfers` | `{sourceAccountId?, destinationAccountId?, destinationType, destinationRef?, amount, transferDate?, note?}` | **never changes net worth** |
| DELETE | `/transfers/{id}` | | reverses the cash effect exactly |
| GET | `/cash-total` | | `{totalCash}` |

`destinationType`: `CASH_ACCOUNT | MUTUAL_FUND | STOCK | FD | RD | EPF | EXTERNAL`.
For a non-cash destination only the cash side is debited — the asset side is created by that
asset's own import path.

---

## Today's actions & Action Center

### `/api/todays-actions`

| Method | Path | Notes |
|---|---|---|
| GET | `/` | Recomputed live on every call — a stale recommendation is never served from storage. |

Response includes `buy[]`, `sellReduce[]`, `bookProfit[]`, `hold[]`, `watch[]`, `notAnalysed[]`,
`portfolioContext`, and `cash`:

```json
"cash": {
  "trackedCash": 0,
  "earmarkedFromRedemptions": 0,
  "cashTracked": false,
  "note": "..."
}
```

`earmarkedFromRedemptions` is a **label on part of `trackedCash`, never added to it** — redemption
proceeds already sit in a bank account, so summing both double-counts.

`BuyAction` carries both `maxAddWithoutBreachingGuideline` (concentration ceiling) and
`suggestedAmount` (that ceiling capped by real cash) plus a `sizingBasis` string naming which
constraint bound. **When no cash account is tracked, `suggestedAmount` is `null`** — no rupee
figure is invented.

### `/api/actions`

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/` | | dispositions for today + active snoozes |
| POST | `/` | `{actionType, symbol, name?, assetType?, amount?, quantity?, status, note?, snoozedUntil?}` | upsert on `(user, actionType, symbol, actionDate)` |
| PATCH | `/{id}/note` | `{note}` | |

`status`: `PENDING | EXECUTED | SKIPPED | SNOOZED` (`snoozedUntil` required when snoozing).
`actionType`: `BUY | REDUCE | BOOK_PROFIT | HOLD | WATCH`.

**`EXECUTED` records only that you placed the trade — it does not change any holding.**

---

## AI review queue — `/api/review`

| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/?all=false` | | pending items (`all=true` for history) |
| GET | `/count` | | `{pending}` |
| POST | `/{id}/decision` | `{decision, correctedType?, correctedAmount?, correctedDate?, correctedCounterparty?, correctedCategory?, note?}` | |

`decision`: `ACCEPT | EDIT | REJECT`. Accepting imports through the normal fingerprint-gated
path. Accepting a non-bookable type (transfer, MF switch, statement-only) records the **judgement
only** and creates no financial row.

---

## Reconciliation — `/api/reconciliation`

| Method | Path | Notes |
|---|---|---|
| GET | `/report` | `{issueCount, issues[]}` across PORTFOLIO / FD / RD / NET_WORTH |

Each issue: `{domain, type, description, severity, referenceId}`; severity `HIGH | MEDIUM | LOW`.

---

## Gmail ingestion — `/api/gmail`

| Method | Path | Notes |
|---|---|---|
| GET | `/auth-url` | returns `{error}` instead of `{url}` when OAuth isn't configured |
| GET | `/callback` | OAuth redirect target — **not** authenticated |
| GET | `/status` | `{connected, connectedEmail, lastSyncAt, importedCount}` |
| POST | `/sync` | 14-day lookback |
| POST | `/resync` | 365 days; clears `ProcessedEmail` **and fingerprints** |
| POST | `/retry-failed` | 30 days; resets failed rows and locked PDFs |
| GET | `/history?limit=100` | per-email audit incl. `REVIEW_REQUIRED` |
| GET | `/reconciliation-report` | persistent, survives page loads |
| GET | `/pending-pdfs` | locked statement attachments |
| POST | `/pending-pdfs/{id}/unlock` | `{password}` — saved encrypted per sender domain on success |
| DELETE | `/pending-pdfs/{id}` | |
| GET/PUT/DELETE | `/saved-passwords[/{id}]` | passwords are never returned |
| GET/POST/DELETE | `/excluded-senders[/{id}]` | exclude a shared-inbox sender |
| POST | `/reprocess-failed` | |
| DELETE | `/disconnect` | |

Sync responses carry a unified `stats` block:

```json
"stats": {
  "lastSync": "...", "scanned": 0, "newlyImported": 0,
  "duplicatesSkipped": 0, "failed": 0,
  "extractedTransactions": 0, "queuedForReview": 0
}
```

---

## Tracking: FD / RD / loans / EPF / other — `/api/tracking`

| Method | Path |
|---|---|
| GET | `/summary` |
| GET POST | `/fd`, `/rd`, `/loan`, `/epf`, `/other` |
| PUT DELETE | `/fd/{id}`, `/rd/{id}`, `/loan/{id}`, `/epf/{id}`, `/other/{id}` |
| POST | `/fd/{id}/close`, `/rd/{id}/close` |

Closing books the interest as an `Income` row with source `Interest` and sets
`status=CLOSED`. Renewal detection runs automatically on create.

---

## Mutual-fund redemptions — `/api/redemptions`

| Method | Path | Notes |
|---|---|---|
| GET | `/` | includes `cashRemaining` (redeemed − reinvested, floored at 0) |
| GET | `/{id}/deployment-plan` | staged tranches with real ATR-derived triggers |
| POST | `/{id}/reinvestments` | `{amount, date?, targetFund?, note?}` — one chunk |

Status flips `ACTIVE → COMPLETED` once fully redeployed.

---

## Cards — `/api/cards`

| Method | Path | Notes |
|---|---|---|
| GET | `/` | includes `pointsCashValue` |
| POST | `/` | `{catalogName}` to prefill from the catalogue, or full custom fields |
| PUT DELETE | `/{id}` | |
| PUT | `/{id}/points` | `{pointsBalance}` |
| GET | `/catalog`, `/categories` | static Indian-card reference data |
| POST | `/recommend` | `{category, amount, merchant?}` → ranked `RecommendResult[]` |
| GET | `/points-tips` | best redemption path per card |

---

## Income & expenses

### `/api/income`
`GET /`, `GET /by-source`, `POST /`, `PUT /{id}`, `DELETE /{id}`, `GET /summary`

`source` is a canonical `IncomeSource`: `Salary | Bonus | Freelance | Business | Dividend |
Interest | Capital Gain | Rental | Other` (legacy free-text is mapped on read).

### `/api/expenses`
`GET /`, `POST /`, `PUT /{id}`, `DELETE /{id}`, `GET /summary`,
`GET|DELETE /miscategorized-investments`

`POST /` **de-duplicates across sources**: same amount + same date + matching merchant or
description returns the existing row instead of creating a second one, so a manual entry can't
double an already-imported bank alert. Merchant strings are normalised on both paths.

---

## Market, analysis & planning

| Group | Endpoints |
|---|---|
| `/api/market` | `GET /overview`, `/quote/{symbol}`, `/search`, `/history/{symbol}`, `POST /history/{symbol}/fetch` |
| `/api/technical` | `GET /{symbol}` |
| `/api/analyst` | `GET /{symbol}` — deterministic factor model + AI narrative |
| `/api/recommendation` | `GET /{symbol}`, `GET /benchmarks`, `POST /mf` |
| `/api/forecast` | `GET /indices`, `GET /` |
| `/api/news` | `GET /`, `GET /symbol/{symbol}` |
| `/api/mf-nav` | `GET /?name=` — AMFI NAV lookup |
| `/api/networth` | `GET /series`, `POST /snapshot` (**server-computed**, takes no body) |
| `/api/goals` | `GET POST /`, `PUT DELETE /{id}` |
| `/api/reminders` | `GET /` |
| `/api/recurring-investments` | `GET POST /`, `DELETE /{id}` |
| `/api/tax` | `GET /summary` |
| `/api/ai` | `POST /analyse-stock`, `/market-summary`, `/portfolio-review/{portfolioId}`, `/chat` |

---

## Errors

`GlobalExceptionHandler` returns a consistent shape and **never leaks stack traces**:

```json
{
  "timestamp": "...", "status": 400, "error": "Bad Request",
  "message": "...", "path": "/api/...", "validationErrors": {}
}
```

Unhandled exceptions return a generic `"An unexpected error occurred"`; the stack trace is logged
server-side only.

| Status | Meaning here |
|---|---|
| 400 | validation failure (e.g. `snoozedUntil` missing when snoozing) |
| 401 | missing/expired token |
| 404 | not found, **or owned by another user** (never distinguished, to avoid leaking existence) |
| 409 | conflict (e.g. resolving an already-resolved review item) |
