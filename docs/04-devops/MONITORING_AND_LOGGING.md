# Monitoring & Logging

## Health checks

`GET /actuator/health` — `permitAll` (no auth). Used by:
- `docker-compose.yml`'s backend healthcheck (30s interval, 5 retries).
- Manual verification during onboarding (`curl http://localhost:8080/actuator/health`).

No other actuator endpoints (`/actuator/metrics`, `/actuator/info`, etc.) were confirmed as
exposed — `springdoc`/`spring-boot-starter-web` is present but a broader actuator surface was not
verified as enabled beyond `/actuator/health` appearing in the `SecurityConfig` allowlist.
`[PARTIALLY IMPLEMENTED / NOT VERIFIED — confirm actuator exposure config
(`management.endpoints.web.exposure.include`) if deeper observability is needed; no such property
was found in `application.yml`, meaning Spring Boot's actuator defaults apply (health only, by
default, beyond what's explicitly enabled).]`

## Logging configuration (`application.yml`)

| Profile | `com.marketai` level | `org.springframework.security` | `root` |
|---|---|---|---|
| dev | `DEBUG` | `INFO` | (unset — Spring default) |
| prod | `INFO` | (unset) | `WARN` |

Console pattern (dev): `%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n`.

No structured/JSON logging, no log aggregation sink (no Logback appender for a log shipper, no
ELK/Loki config) was found — logs go to stdout/console only, which is standard for a
container-orchestrated deployment (Docker/K8s log drivers pick up stdout) but means any
centralized log search depends entirely on whatever the deployment platform provides around the
container, not on anything configured in this codebase.

## What the system logs today (application-level signal, not infra monitoring)

These are the built-in "loud failure" points already in the code — treat them as the monitoring
signals worth alerting on until real observability tooling is added:

| Signal | Where | Why it matters |
|---|---|---|
| `"AMFI NAV refresh: X bytes returned but no scheme rows parsed"` | `AmfiNavService.refresh()` | MF NAV data silently stopped updating |
| Gmail watch registration failure | `GmailWatchService.registerWatch` (ERROR level) | Push notifications will lapse — watches expire after 7 days with **no error from Gmail itself**, so this app-level log is the only signal |
| `SyncJob` terminal `FAILED` status with `lastError` | `sync_jobs` table, queryable via `GET /api/sync/jobs` | A Gmail sync exhausted its 3 retries |
| `"Worker did not finish; exceeded retry budget"` | `SyncJobService.requeueStale` | A sync worker died mid-job and the job couldn't be salvaged |
| Per-scheme/per-ticker isolated failures | `MfNavHistoryScheduler`, `MarketDataService.scheduledSectorIndexHistoryRefresh` | One bad symbol doesn't abort the batch, but repeated single-symbol failures are worth surfacing |
| `AiAuditTrail` rows with `status != ACCEPTED` | `ai_audit_trail` table | Every rejected/low-confidence AI classification is recorded — a spike here signals either a parser regression or a genuinely unusual batch of emails |

## Recommended additions (not yet built)

- Wire `management.endpoints.web.exposure.include` to add `/actuator/metrics`,
  `/actuator/prometheus` (if adopting Prometheus) for real infra-level monitoring.
  `[PLANNED / NOT IMPLEMENTED]`.
- Alert on the `SyncJob` FAILED-status count and on `GmailWatchService` renewal failures — both
  already produce the right log signal, just nothing consumes it externally today.
- Structured logging (JSON) would make the existing signal list above machine-parseable by a log
  aggregator without code changes beyond the Logback encoder config.
