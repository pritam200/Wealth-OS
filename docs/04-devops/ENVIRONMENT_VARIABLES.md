# Environment Variables — Master Matrix

Extracted directly from `backend/src/main/resources/application.yml` (both `dev` and `prod`
profile documents) plus `frontend/.env`. Every `${VAR:default}` pattern in the codebase is listed.

**Critical caveat, verified in code**: Spring's multi-document YAML means each `---`-separated
profile document is **independent** — a key set only in the `dev` document has **no value at all**
when running with `SPRING_PROFILES_ACTIVE=prod` unless it's also redefined in the `prod` document
or has a `${VAR:default}` fallback inlined at the Java `@Value` use site. Several keys fall into
this gap — marked below.

## Backend

| Variable | Description | Required (Y/N) | Default | Role |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Active profile | N | `dev` | Backend |
| `DB_HOST` | Postgres host | Y (prod) / N (dev) | `localhost` | Backend/DevOps |
| `DB_PORT` | Postgres port | Y (prod) / N (dev) | `5432` | Backend/DevOps |
| `DB_NAME` | Database name | Y (prod) / N (dev) | `marketai_db` | Backend/DevOps |
| `DB_USER` | Database user | Y (prod) / N (dev) | `marketai` | Backend/DevOps |
| `DB_PASSWORD` | Database password | Y (prod) / N (dev) | `marketai_pass` | Backend/DevOps |
| `REDIS_HOST` | Redis host | Y (prod) / N (dev) | `localhost` | Backend/DevOps |
| `REDIS_PORT` | Redis port | Y (prod) / N (dev) | `6379` | Backend/DevOps |
| `REDIS_PASSWORD` | Redis password | Y (prod) / N (dev) | `` (empty) | Backend/DevOps |
| `JWT_SECRET` | JWT signing secret | Y (prod, no default) / N (dev) | dev: none — auto-generates and persists to gitignored `gmail.env` if unset | Backend |
| `GEMINI_API_KEY` | Google Gemini API key | Y (prod, no default) / N (dev) | `` | Backend |
| `NEWS_API_KEY` | NewsAPI key (prod news source) | Y (prod, no default) | — not present in dev block at all; dev uses free RSS feeds | Backend |
| `CORS_ALLOWED_ORIGINS` | Allowed CORS origins (dev only) | N | `http://localhost:3000,http://localhost:5173,http://localhost:5174` | Backend |
| `FRONTEND_URL` | Where OAuth callback redirects the browser; also prod's single CORS origin | Y (prod, no default) / N (dev) | dev: `http://localhost:5174` | Backend |
| `PDF_PASSWORD_ENC_KEY` | Base64 32-byte AES-256 key for encrypting saved statement passwords | N | random/ephemeral if unset (generate with `openssl rand -base64 32`) | Backend |
| `GMAIL_CLIENT_ID` | Google OAuth client ID | N (but Gmail sync won't work without it) | `` | Backend |
| `GMAIL_CLIENT_SECRET` | Google OAuth client secret | N (same caveat) | `` | Backend |
| `GMAIL_REDIRECT_URI` | Must exactly match the OAuth client's registered redirect URI | N | `http://localhost:8080/api/gmail/callback` | Backend |
| `GMAIL_PUSH_ENABLED` | Enable Gmail push (watch + Pub/Sub) | N | `false` | Backend |
| `GMAIL_PUSH_TOPIC` | Google Cloud Pub/Sub topic name | N (required if push enabled) | `` | Backend |
| `GMAIL_PUSH_TOKEN` | Shared-secret query token for the push webhook | N (required if push enabled) | `` | Backend |
| `LLM_PROVIDER` | `ollama` \| `gemini` \| `none` | N | `ollama` | Backend |
| `OLLAMA_BASE_URL` | Ollama server URL | N | `http://localhost:11434` | Backend |
| `OLLAMA_MODEL` | Ollama model name | N | `qwen2.5:7b` | Backend |

## Backend — dev-only YAML keys with no `prod` document entry

These resolve purely to their Java-side `@Value("${x:default}")` inline default when
`SPRING_PROFILES_ACTIVE=prod` — confirmed for the sync-worker settings; treat the rest as the
same pattern unless verified otherwise:

| Key | Effective prod value | Source of default |
|---|---|---|
| `app.sync.worker.concurrency` | `2` | `SyncJobWorker.java` `@Value("${app.sync.worker.concurrency:2}")` |
| `app.sync.worker.stale-after-minutes` | `90` | `SyncJobWorker.java` `@Value("${app.sync.worker.stale-after-minutes:90}")` |
| `app.gmail.push.*` (all 3 keys) | as documented above | inline defaults |
| `app.llm.*` (all keys) | as documented above | inline defaults |
| `app.recommendation.weights.*` | technical=30, momentum=20, valuation=20, sentiment=30 | only in `dev` document |
| `app.security.pdf-password-key` | random/ephemeral | only in `dev` document |
| `gmail.client-id`/`client-secret`/`redirect-uri`/`frontend-url` | as documented above | only in `dev` document |
| `springdoc.*` | Swagger UI paths | only in `dev` document |
| `spring.cache.type` | `simple` | only in `dev` document |

**Also has no YAML entry in either profile**: `app.market.refresh-rate-ms` — driven entirely by
the inline `@Scheduled(fixedRateString = "${app.market.refresh-rate-ms:900000}")` default (15
min) in `MarketDataService.java:204`. Cannot be tuned via config without a code change.

## Backend — hardcoded, not configurable via env var

| Value | Where |
|---|---|
| `app.yahoo-finance.base-url` = `https://query1.finance.yahoo.com` | both profiles |
| `app.yahoo-finance.timeout-seconds` = `10` | both profiles |
| `app.gemini.base-url` = `https://generativelanguage.googleapis.com/v1beta` | both profiles |
| `app.gemini.model` = `gemini-1.5-flash` | both profiles |
| `app.jwt.expiration-ms` = `86400000` (24h), `app.jwt.refresh-expiration-ms` = `604800000` (7d) | both profiles |
| `app.llm.ollama.timeout-seconds` = `120`, `app.llm.ollama.temperature` = `0.0` | dev only (see gap above) |
| `app.llm.min-confidence` = `0.85` | dev only (see gap above) |
| `hibernate.jdbc.time_zone` = `Asia/Kolkata` | dev only |
| `spring.jpa.hibernate.ddl-auto` = `update` (dev) / `validate` (prod) | both, different values |

## Frontend

| Variable | Description | Required (Y/N) | Default | Role |
|---|---|---|---|---|
| `VITE_API_URL` | Backend base URL | N | `http://localhost:8080` | Frontend |
| `BACKEND_URL` | Used by `docker-compose.yml` to set the frontend build's `VITE_API_URL` | N | `http://localhost:8080` | DevOps |

**Note**: `VITE_API_URL` is baked into the static build at **build time** (Vite env vars are
compile-time only) — changing it at runtime requires a rebuild, not just an env var change on
the running container.

## `.env.example` gap (verified against `application.yml`)

The repo-root `.env.example` declares: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE`,
`GEMINI_API_KEY`, `NEWS_API_KEY`, `FRONTEND_URL`, `BACKEND_URL`.

**It omits** (all of which `application.yml` reads): `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`,
`GMAIL_REDIRECT_URI`, `GMAIL_PUSH_ENABLED`, `GMAIL_PUSH_TOPIC`, `GMAIL_PUSH_TOKEN`,
`LLM_PROVIDER`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `PDF_PASSWORD_ENC_KEY`,
`CORS_ALLOWED_ORIGINS`. `[PARTIALLY IMPLEMENTED — a new engineer following `.env.example` alone
will hit silent defaults for all of these.]`
