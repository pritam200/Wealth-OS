# Jobs & Schedulers

## `@Scheduled` inventory (every scheduled method in the backend)

| File:line | Trigger | Method | Purpose |
|---|---|---|---|
| `amfi/service/AmfiNavService.java:47` | `cron="0 30 21 * * *"` | `refresh()` | Daily 21:30: fetches AMFI's `NAVAll.txt` (redirect-following WebClient — the `www` host 302s to `portal.amfiindia.com`), parses into an in-memory `AtomicReference<List<AmfiNavResult>>` cache. Logs explicitly on empty response or zero parsed rows. |
| `mf/scheduler/MfNavHistoryScheduler.java:30` | `cron="0 0 22 * * *"` | `refreshHeldSchemeHistory()` | Daily 22:00 (after AMFI's 21:30 refresh, by design): links unlinked MF holdings to AMFI scheme codes, then tops up NAV history per distinct linked scheme, isolating per-scheme failures. |
| `news/service/NewsService.java:63` | `fixedRateString="${app.news.refresh-rate-ms:1800000}"` (30 min) | `fetchLatestMarketNews()` | Pulls 7 hardcoded free RSS feeds (dev); per-feed failures logged at debug, don't abort the rest. |
| `portfolio/scheduler/HoldingReconciliationScheduler.java:25` | `fixedDelay=86_400_000, initialDelay=60_000` (daily) | `scheduledReconciliation()` | Calls `PortfolioService.reconcileAllUsers()` — replays every holding's ledger, logs drift. |
| `tracking/scheduler/DepositMaturityScheduler.java:23` | `fixedDelay=86_400_000, initialDelay=30_000` (daily) | `scheduledMaturityCheck()` | Calls `TrackingService.markMaturedDeposits()` — flips ACTIVE → MATURED past maturity date. |
| `market/service/MarketDataService.java:204` | `fixedRateString="${app.market.refresh-rate-ms:900000}"` (15 min — **no YAML entry**, pure annotation default) | `refreshMarketData()` | `@CacheEvict(allEntries=true)` on `marketQuotes`/`indexData`/`technicals` — a pure cache-bust, no fetch. |
| `market/service/MarketDataService.java:297` | `cron="0 30 18 * * MON-FRI", zone="Asia/Kolkata"` | `scheduledSectorIndexHistoryRefresh()` | 18:30 IST weekdays (after NSE close + Yahoo's final-bar settle) → persists 1y of daily bars for 10 sector indices + Nifty benchmark, isolating per-ticker failures. |
| `sync/worker/SyncJobWorker.java:73` | `fixedDelay=5_000, initialDelay=10_000` | `poll()` | Drains the sync-job queue — see mechanics below. |
| `sync/worker/SyncJobWorker.java:105` | `fixedDelay=600_000, initialDelay=600_000` (10 min) | `reclaimStale()` | Requeues jobs stuck `RUNNING` past 90 min (crashed worker safety net). |
| `gmail/scheduler/GmailSyncScheduler.java:40` | `fixedDelay=1_800_000` (30 min) | `scheduledSync()` | Safety-net poll: enqueues a `GMAIL_INCREMENTAL_SYNC` job per `GmailToken` — needed since Gmail push caps at 1 notification/sec/user. |
| `gmail/scheduler/GmailSyncScheduler.java:61` | `fixedDelay=86_400_000, initialDelay=120_000` (daily) | `renewWatches()` | Renews Gmail push "watch" registrations (expire after 7 days, silently, with no callback). |

**Note**: `app.market.refresh-rate-ms` and several other `@Value("${x:default}")` defaults
inlined at the annotation site have no corresponding entry in `application.yml` at all — see
`04-devops/ENVIRONMENT_VARIABLES.md` for the full list of config that only exists as a Java-side
default.

## Async job queue (`sync/` package) — the mechanism behind Gmail sync

This is the general-purpose durable job queue; today its only job type is Gmail sync, but the
design (`SyncJobType` enum, atomic claim, retry/reclaim) is not Gmail-specific.

### Concurrency

`SyncJobWorker` builds a fixed-size `ThreadPoolExecutor(concurrency, concurrency, 0L, MILLISECONDS,
LinkedBlockingQueue<>())` with **daemon** threads named `sync-worker`. `concurrency` =
`app.sync.worker.concurrency` (default **2**). Deliberately small: the bottleneck is the local LLM
classifier (~8s/email measured), and parallelizing on one machine makes each job slower rather
than the total faster — it also keeps one in-flight job per user under Gmail's 6,000-units/min
per-user quota.

### In-flight tracking

A `ConcurrentHashMap.newKeySet<Long>()` of job IDs this JVM currently owns, so the 5s poller
never re-dispatches a job it's already running locally.

### Startup reclaim

`@PostConstruct start()` immediately calls `jobService.requeueStale(Duration.ZERO)` — anything
left `RUNNING` belongs to a previous process that died; reclaimed unconditionally at boot.

### Atomic claiming — the concurrency-safety mechanism

```sql
UPDATE SyncJob SET status = RUNNING, claimedBy = :worker, claimedAt = :now,
    startedAt = COALESCE(startedAt, :now), attempts = attempts + 1
WHERE id = :id AND status = QUEUED
```

`@Modifying(clearAutomatically=true, flushAutomatically=true)`, returns rows-affected. Exactly
one worker's `UPDATE` matches even if two workers read the same `QUEUED` row simultaneously — the
status check lives in the `WHERE` clause specifically so a read-then-write race in application
code (which would let both proceed and double-import) is structurally impossible.

### Duplicate-enqueue guard

`SyncJobService.enqueue()` checks `existsByUser_IdAndTypeAndStatusIn(userId, type, [QUEUED,
RUNNING])` before inserting — collapses a burst of Pub/Sub at-least-once push notifications into
one sync per mailbox, not one per notification.

### Retry & stale-job reclaim

- `SyncJobService.fail(jobId, error)`: sets `lastError` (truncated 1000 chars); requeues to
  `QUEUED` if `attempts < maxAttempts` (default 3), else terminal `FAILED` + `finishedAt`. Most
  failures are treated as transient (token refresh, Gmail 5xx, slow model call).
- `requeueStale(Duration)`: finds `RUNNING` jobs with `claimedAt` older than the threshold;
  requeues if attempts remain, else `FAILED` with `"Worker did not finish; exceeded retry
  budget."`. Called both at startup (`Duration.ZERO`) and every 10 minutes
  (`Duration.ofMinutes(staleAfterMinutes)`, default 90).

### Failure isolation

The `pool.submit()` lambda wraps `runner.run(jobId)` in try/catch — an escaping `Throwable` is
caught, logged, and routed to `jobService.fail()` specifically because an uncaught exception on a
pool thread would silently kill that thread and permanently shrink capacity.

### Entry points that enqueue a job

| Source | Type | Trigger |
|---|---|---|
| `POST /api/sync/gmail` | `GMAIL_FULL_SYNC` or `GMAIL_INCREMENTAL_SYNC` | `MANUAL` |
| `GmailSyncScheduler.scheduledSync()` | `GMAIL_INCREMENTAL_SYNC` | `SCHEDULED` |
| `GmailPushController.receive()` | `GMAIL_INCREMENTAL_SYNC` | `PUSH` |
| (implicit) stale-job reclaim | same type, requeued | `RECOVERY` conceptually (see `SyncTrigger.RECOVERY`, though the reclaim path reuses the original job's trigger rather than rewriting it) |

## Legacy inline sync path (still present)

`POST /api/gmail/sync` (`GmailController.triggerSync`) runs `GmailSyncService.doSyncForUser`
synchronously, blocking the HTTP request for the full duration of the scan (including any AI
classification calls). Kept for backward compatibility — `POST /api/sync/gmail` is the intended
path for new integrations since it returns immediately with a pollable job.
