# Feature Matrix

Status tags: `[IMPLEMENTED]` (working, verified in code) · `[PARTIALLY IMPLEMENTED]` (real gap or
inconsistency exists) · `[PLANNED / NOT IMPLEMENTED]` (no working code).

## Portfolio & Wealth

| Feature | Status | Evidence |
|---|---|---|
| Stock/MF holdings via transaction ledger | `[IMPLEMENTED]` | `PortfolioService.recomputeFromLedger` — holdings are never hand-set, always replayed from `Transaction` rows |
| Net worth (single source of truth) | `[IMPLEMENTED]` | `PortfolioContextService.build()` — one formula, consumed by `/api/wealth/summary` |
| Cash accounts counted in net worth | `[IMPLEMENTED]` | `CashAccount` + `cashAccountRepository.sumBalanceByUser` folded into `totalAssets` |
| Internal transfers (net-worth-neutral) | `[IMPLEMENTED]` | `LedgerTransferService.applyCashEffect` — equal-and-opposite balance mutation |
| Real XIRR (Newton-Raphson) | `[IMPLEMENTED]` | `XirrCalculator` — derived from actual cash flows, not a stored/guessed field |
| FD/RD renewal auto-linking | `[IMPLEMENTED]` | `TrackingService.detectAndLinkRenewal` / `detectAndLinkRdRenewal` — date-window + amount-tolerance match |
| MF redemption STCG/LTCG tax | `[IMPLEMENTED]` | `RedemptionService.recordRedemption` — 15%/12.5% rates, ₹1.25L LTCG exemption applied per redemption (not tracked cumulatively across the tax year) |
| Deployment plan for redeemed cash | `[IMPLEMENTED]` (rule-based) | `RedemptionService.getDeploymentPlan` — 20/30/50 split; explicitly documented as NOT live AI monitoring |
| Portfolio integrity/reconciliation check | `[IMPLEMENTED]` | `PortfolioController.integrityCheck`, `ReconciliationController` |
| Nightly holding drift reconciliation | `[IMPLEMENTED]` | `HoldingReconciliationScheduler` (daily) → `PortfolioService.reconcileAllUsers` |
| Lot-level (FIFO/LIFO) cost basis | `[PLANNED / NOT IMPLEMENTED]` | `recomputeFromLedger` uses weighted-average-cost replay only, not per-lot tracking |
| Cumulative-year LTCG exemption tracking | `[PLANNED / NOT IMPLEMENTED]` | Exemption applied per-redemption in `RedemptionService`, not aggregated across the financial year |

## Gmail Ingestion

| Feature | Status | Evidence |
|---|---|---|
| OAuth connect/callback | `[IMPLEMENTED]` | `GmailClientService`, `GmailController.callback` |
| Full-scan sync (14-day window default) | `[IMPLEMENTED]` | `GmailSyncService.doSyncForUser` |
| Incremental sync (Gmail history API) | `[IMPLEMENTED]` | `GmailIncrementalSyncService.fetchDelta` — historyId watermark, monotonic-forward only |
| Async job queue (non-blocking sync) | `[IMPLEMENTED]` | `SyncJob`/`SyncJobWorker`/`SyncJobRunner` — atomic claim, retry, stale-job reclaim |
| Push notifications (Gmail watch + Pub/Sub) | `[IMPLEMENTED]` | `GmailWatchService`, `GmailPushController` — disabled by default (`app.gmail.push.enabled=false`) |
| 18 institution-specific parsers | `[IMPLEMENTED]` | See `02-backend/GMAIL_INGESTION_PIPELINE.md` §4 for the full list |
| Generic bank-alert catch-all parser | `[IMPLEMENTED]` | `BankTransactionParser` (`@Order(100)`, runs last) |
| Password-protected PDF unlock | `[IMPLEMENTED]` | `PdfImportService` — 3-tier password resolution (exact/cross-PAN/hint-based) |
| SHA-256 transaction fingerprint dedup | `[IMPLEMENTED]` | `TransactionFingerprinter` + `ImportedTransactionFingerprint` unique constraint |
| Local LLM classification fallback | `[IMPLEMENTED]` | `EmailIntelAgent` via Ollama, 85% confidence gate |
| Human review queue | `[IMPLEMENTED]` | `EmailReviewItem`/`EmailReviewService`, Accept/Edit/Reject |
| Excluded sender filtering | `[IMPLEMENTED]` | `ExcludedSender` entity, checked before any parser runs |
| True MCC (card-network) category codes | `[PLANNED / NOT IMPLEMENTED]` | `SpendAggregator` explicitly documents categories as merchant-name-inferred, not real MCCs |

## Signal Engine & Recommendations

| Feature | Status | Evidence |
|---|---|---|
| Multi-factor signal scoring (family-weighted) | `[IMPLEMENTED]` | `SignalEngine` — trend/momentum/structure/volume/volatility, one vote per family |
| ATR-based stop-loss/take-profit | `[IMPLEMENTED]` | `SignalEngine.buildExecution` — 2×ATR stop, 4×ATR target (fixed 2:1 R:R) |
| Confidence capped by data availability | `[IMPLEMENTED]` | `confidence = min(|composite|, ceiling)` — a partial read can't claim full confidence |
| Position sizing from real cash balance | `[PLANNED / NOT IMPLEMENTED]` | `buildExecution` explicitly sets `limitedBy="NOT_SIZED"` — "would be invented" |
| Order-book / microstructure factor | `[PLANNED / NOT IMPLEMENTED]` | Permanently null — "requires a broker L2 feed; Yahoo does not publish depth" |
| Today's Investment Actions daily queue | `[IMPLEMENTED]` | `TodaysActionsService`, `ActionItem` with execute/snooze/note |
| AI portfolio review / chat | `[IMPLEMENTED]` | `AiController` (`/api/ai/*`), Gemini-backed |

## Cards & Rewards

| Feature | Status | Evidence |
|---|---|---|
| Card CRUD + reward-rate catalog | `[IMPLEMENTED]` | `CreditCard` entity, `card_reward_rates` element collection |
| Net-value optimizer (Keep/Downgrade/Cancel) | `[IMPLEMENTED]` | `CardOptimizerService.evaluate` — verdict gated by 90-day spend coverage ≥50% |
| Best-card-for-category recommender | `[IMPLEMENTED]` | `CardController.recommend` |
| Effective-dated reward rules with staleness check | `[IMPLEMENTED]` | `CardRewardRule.isCurrentOn`/`isStale` |
| 12-month realized reward tracking | `[PLANNED / NOT IMPLEMENTED]` | `rewardsRealized12m` is always `null` — "not yet backed by 12 months of per-card reward history" |
| Points balance / wallet | `[IMPLEMENTED]` | `CreditCard.pointsBalance`, `pointsTips` endpoint |

## Financial Planning

| Feature | Status | Evidence |
|---|---|---|
| Goals | `[IMPLEMENTED]` | `FinancialGoal` entity, `GoalController` |
| Reminders | `[IMPLEMENTED]` (read-only) | `ReminderController` — only a `GET /api/reminders` exists, no create/update/delete endpoint found |
| Tax summary | `[IMPLEMENTED]` | `TaxController` |
| Recurring investments (SIP/PPF/NPS) | `[IMPLEMENTED]` | `RecurringInvestment` entity, `RecurringInvestmentController` |

## Platform / Cross-cutting

| Feature | Status | Evidence |
|---|---|---|
| JWT auth (access + refresh) | `[IMPLEMENTED]` | `JwtAuthFilter`, jjwt 0.12.6 |
| Role-based admin routes | `[PARTIALLY IMPLEMENTED]` | `SecurityConfig` reserves `/api/admin/**` for `ROLE_ADMIN`; **no controller maps under that path** — dead/reserved rule |
| Privacy masking (hide wealth) | `[IMPLEMENTED]` | `usePrivacyStore`, `Amount`/`useMaskedText` — convention-enforced, not type-enforced |
| CI/CD pipeline | `[PLANNED / NOT IMPLEMENTED]` | No `.github/workflows`, `Jenkinsfile`, or `.gitlab-ci.yml` anywhere in the repo |
| Server-state caching (React Query/SWR) | `[PLANNED / NOT IMPLEMENTED]` | Confirmed zero usage — every fetch is manual `useState`+`useEffect`+axios |
