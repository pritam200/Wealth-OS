# Roadmap & Known Gaps

Grounded in code inspection, not a planning document — every gap below was found by reading the
actual implementation.

## Known gaps (found in code, not designed-in)

| Gap | Where | Impact |
|---|---|---|
| **No CI/CD pipeline** | Repo-wide search: no `.github/workflows`, `Jenkinsfile`, `.gitlab-ci.yml` | Every deploy is manual; no automated test gate before merge |
| **Java version mismatch** | `pom.xml` targets Java 25; `backend/Dockerfile` builds on `eclipse-temurin:21` | The Docker build would fail against the current `pom.xml` unless the image has been bumped since last verified |
| **`.env.example` is incomplete** | Omits `GMAIL_CLIENT_ID/SECRET/REDIRECT_URI`, `GMAIL_PUSH_*`, `LLM_PROVIDER`, `OLLAMA_*`, `PDF_PASSWORD_ENC_KEY`, `CORS_ALLOWED_ORIGINS` | A new engineer following `.env.example` alone will hit unexplained blank/default config in prod |
| **Prod profile missing several `dev`-only keys** | `application.yml` — `app.sync.worker.*`, `app.gmail.push.*`, `app.llm.*`, `app.recommendation.weights`, `app.security.pdf-password-key`, Gmail OAuth block, springdoc, `spring.cache.type` have no entry in the `prod` YAML document | These resolve purely to the Java-side `@Value("${x:default}")` fallback in prod — works, but is undocumented and fragile if that default ever needs to differ from dev |
| **`app.market.refresh-rate-ms` has no YAML entry at all** | Driven only by the `@Scheduled` annotation's inline `:900000` default in `MarketDataService.java:204` | Cannot be tuned via config without a code change |
| **`/api/admin/**` is a dead security rule** | `SecurityConfig` reserves the path for `ROLE_ADMIN`, but no controller maps under it | Harmless today; a reminder that an admin surface was planned but never built |
| **`PortfolioPage.tsx` is orphaned** | 279 lines, confirmed unreferenced by any route or tab | Dead code — safe to delete, but currently just sits unreachable |
| **Two unused/dangling frontend artifacts** | `MfHoldingsSignals` exported but never wired into `TabContent`; stray empty `src/pages/{auth}` directory (literal braces, mkdir accident) | Cosmetic, but worth cleaning during a frontend pass |

## Explicitly deferred / not-yet-real features

These are documented in code comments as intentional simplifications, not oversights:

- **Order-book / market-microstructure signal factor** — permanently `null` in `SignalEngine`.
  Requires a broker-provided Level 2 feed; Yahoo Finance (the only market data source) does not
  publish depth. No workaround exists without a new data vendor.
- **Position sizing in signal execution** — never computed (`limitedBy="NOT_SIZED"`). Requires a
  real equity figure and tracked cash balance; the engine refuses to invent one.
- **True MCC (card-network) categories** — `SpendAggregator` infers categories from merchant
  names; a real MCC feed would require a card-network/issuer data partnership.
- **12-month realized card-reward tracking** — `rewardsRealized12m` is always `null`; the ledger
  doesn't yet carry enough per-card reward history to report a measured (vs. projected) figure.
- **Cumulative-year LTCG exemption tracking** — the ₹1.25L exemption is applied per-redemption,
  not aggregated across the financial year across all redemptions.
- **FIFO/LIFO lot-level cost basis** — holdings use weighted-average-cost replay only.

## Recently completed (visible in code as recent, deliberate architecture work)

- Java 8 → Java 25 / Spring Boot 2.7 → 3.5.3 migration (jakarta namespace, jjwt 0.12 API,
  Spring Security 6 `requestMatchers`, springdoc 2.x, PDFBox 3.x).
- Async Gmail sync via a durable job queue (`SyncJob`/`SyncJobWorker`) replacing an inline,
  request-blocking sync call.
- Gmail incremental sync via the History API, replacing a full 300-message re-scan every run.
- Local LLM (Ollama) email classification with a human review queue as the confidence backstop.
- Cash-account tracking added specifically to fix a net-worth-inflation bug caused by unmodeled
  internal transfers.
- Family-weighted signal engine (v2.0.0) replacing a naive per-indicator average.

## Open questions for product/stakeholders

- Should `/api/admin/**` be built out, or should the dead security rule be removed?
- Is a real MCC data source (card-network partnership) worth pursuing, or does the merchant-name
  inference meet the bar for the Card Optimizer's intended accuracy?
- Should FIFO/LIFO cost-basis tracking be prioritized for tax-reporting accuracy, or is
  weighted-average sufficient for this product's stated use case?
