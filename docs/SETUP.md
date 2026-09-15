# Wealth-OS — Environment & Setup

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **8** | Hard requirement. `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home` on this machine |
| Maven | 3.6+ | wrapper not used |
| Node | 18+ | Vite 8 / React 19 |
| PostgreSQL | 16 | via Docker or local |
| Redis | 7 | optional in dev |
| Ollama | any | optional — for the local AI layer |

---

## Quick start

### 1. Database

```bash
docker compose up -d postgres redis
```

`database/schema.sql` and `database/seed.sql` are applied automatically on **first** container
creation only. Hibernate runs `ddl-auto: update` in dev, so new entities create their own tables
on boot — you rarely need to touch the SQL files.

### 2. Backend

```bash
cd backend
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home mvn -o spring-boot:run
```

Serves on `:8080`. Wait for `Started MarketAiApplication`.

### 3. Frontend

```bash
cd frontend && npm install && npm run dev
```

Serves on `:5173`.

### 4. (Optional) Local AI

```bash
ollama serve
ollama pull qwen2.5:7b
```

---

## Environment variables

Nothing is required to boot in dev — every variable has a safe default or is optional. Set them
in your shell, a `.env` consumed by docker-compose, or `backend/gmail.env` (gitignored).

### Database

| Variable | Default (dev) | Notes |
|---|---|---|
| `DB_HOST` | `localhost` | |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `marketai_db` | |
| `DB_USER` | `marketai` | |
| `DB_PASSWORD` | `marketai_pass` | ⚠️ committed dev default — see Security below |

### Security

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | *(none)* | **No default.** When unset, `JwtService` generates a 512-bit `SecureRandom` secret on first boot and persists it to `backend/gmail.env` (gitignored). Set explicitly for anything deployed. |
| `PDF_PASSWORD_ENC_KEY` | *(none)* | AES-256 base64 key encrypting saved statement passwords. Auto-generated and persisted to `gmail.env` the same way. Generate manually with `openssl rand -base64 32`. |

### AI layer

| Variable | Default | Notes |
|---|---|---|
| `LLM_PROVIDER` | `ollama` | `ollama` \| `gemini` \| `none`. `none` disables all model calls; deterministic parsing and every calculation keep working. |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `OLLAMA_MODEL` | `qwen2.5:7b` | 7b for throughput (a sync classifies many emails); `qwen2.5:14b` for accuracy on low-volume tasks |
| `GEMINI_API_KEY` | *(empty)* | only needed for `LLM_PROVIDER=gemini` |

Non-env tunables in `application.yml`: `app.llm.min-confidence` (default `0.85`),
`app.llm.ollama.timeout-seconds` (120), `app.llm.ollama.temperature` (**0.0** — pinned so
classification is reproducible), `app.llm.fallback-enabled`.

### Gmail ingestion

| Variable | Default | Notes |
|---|---|---|
| `GMAIL_CLIENT_ID` | *(empty)* | Google Cloud OAuth client |
| `GMAIL_CLIENT_SECRET` | *(empty)* | |
| `GMAIL_REDIRECT_URI` | `http://localhost:8080/api/gmail/callback` | must **exactly** match an Authorized redirect URI on the OAuth client |
| `FRONTEND_URL` | `http://localhost:5174` | where the OAuth callback redirects back to |

Gmail features degrade gracefully when unconfigured — `/api/gmail/auth-url` returns an
explanatory `{error}` rather than failing.

### Other

| Variable | Default |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `dev` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173,http://localhost:5174` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(empty)* |
| `NEWS_API_KEY` | *(prod only)* |
| `VITE_API_URL` (frontend) | `http://localhost:8080` |

**Note on ports:** the backend defaults `FRONTEND_URL` to `:5174` while Vite serves `:5173`. If
the Gmail OAuth redirect lands on the wrong port, set `FRONTEND_URL=http://localhost:5173`.

---

## Common commands

Backend compile / test:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home mvn -o -q -f backend/pom.xml compile
```

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_301.jdk/Contents/Home mvn -o -q -f backend/pom.xml test
```

Single test class:

```bash
mvn -o -q -f backend/pom.xml test -Dtest=LedgerTransferServiceTest
```

> Surefire 2.22 wants **comma**-separated names (`-Dtest=A,B`); the `+` syntax silently runs
> nothing and fails with "No tests were executed".

Frontend typecheck — this exact invocation is the only one that works here:

```bash
cd frontend && npx tsc --noEmit -p tsconfig.app.json
```

Restart the backend (it does not hot-reload):

```bash
pgrep -f 'com.marketai.MarketAiApplication' | xargs -I{} kill {}
```

```bash
lsof -i :8080 -sTCP:LISTEN
```

Inspect the database:

```bash
PGPASSWORD=marketai_pass psql -h localhost -U marketai -d marketai_db -c "\dt"
```

Check Ollama:

```bash
curl -s http://localhost:11434/api/tags
```

---

## Verification checklist

Before calling a change done:

1. Backend compiles.
2. Full test suite passes — check the exit code and the absence of `[ERROR] Tests run` lines;
   `-q` output alone is misleading.
3. Frontend typechecks.
4. Backend restarts and logs `Started MarketAiApplication`.
5. For UI work: load the affected tab in a browser and check the console. Type-checking proves
   the code compiles, not that the feature works.

---

## Troubleshooting

**Port 8080 already in use** — a previous `mvn spring-boot:run` or an IDE-launched instance is
still holding it. Find it with `lsof -i :8080 -sTCP:LISTEN` and kill that PID. On macOS, `lsof`
prints harmless `can't stat() ... hfs file system` warnings; filter with `grep -v WARNING`.

**Tailwind changes not appearing** — a long-running Vite process can end up with a stale config
watcher. Confirm with `getComputedStyle(document.body).backgroundColor` in the console; if it
still shows the old value, restart the dev server.

**Backend won't start after adding an entity** — in dev `ddl-auto: update` only adds; it never
drops or narrows. If a column type changed, drop the table and let it be recreated.

**`@Lob String` becomes an `oid` column on PostgreSQL** — large-object pointers need explicit
lifecycle management and orphan their storage. Use `@Column(columnDefinition = "text")` instead.

**AMFI NAV fetch fails with PKIX / SSL** — a known outstanding issue with the certificate chain
for `amfiindia.com`; NAVs won't refresh from that source.

**Sessions dropped after restart** — `JWT_SECRET` wasn't persisted (check `backend/gmail.env` is
writable). Set it explicitly to be sure.

---

## Security notes

- `JWT_SECRET` previously shipped a **literal committed signing key** as the dev default, which
  would let anyone with the repo mint a valid token for any account. That default is removed; see
  the table above for the replacement.
- `DB_PASSWORD` still has a committed dev default. It's a local-only database, but it remains a
  committed credential — move it to `gmail.env` if that matters to you.
- Logs never contain tokens, passwords or prompts. `ai_audit_trail` **does** store prompts by
  design, with secret redaction applied on write.
- `backend/gmail.env` is gitignored and holds auto-generated local secrets. Don't commit it.
- The prod profile (`SPRING_PROFILES_ACTIVE=prod`) requires `DB_PASSWORD`, `JWT_SECRET`,
  `GEMINI_API_KEY` and `NEWS_API_KEY` with **no defaults**, and switches Hibernate to
  `ddl-auto: validate`.
