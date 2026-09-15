# Backend Onboarding

## Prerequisites (verified versions)

| Tool | Version | Source |
|---|---|---|
| JDK | **25** | `pom.xml`: `java.version`/`maven.compiler.release=25` |
| Maven | 3.6+ | standard |
| PostgreSQL | 16 | `docker-compose.yml` |
| Redis | 7 | `docker-compose.yml` (optional in dev — `spring.cache.type=simple` doesn't require it) |
| Ollama | any, with `qwen2.5:7b` pulled | `app.llm.provider=ollama` default |

**Known discrepancy**: `backend/Dockerfile` builds/runs on `eclipse-temurin:21-*-alpine`, which
does not match `pom.xml`'s Java 25 requirement. Verify which is authoritative before relying on
the Docker build — see `04-devops/DEPLOYMENT_GUIDE.md`.

## Quick start

### 1. Database (via Docker Compose, from repo root)

```bash
docker compose up -d postgres redis
```

Postgres initializes from `./database/schema.sql` then `./database/seed.sql` on first boot
(mounted as `docker-entrypoint-initdb.d`).

### 2. Environment

Copy `.env.example` to `.env` — **note it is incomplete** relative to what `application.yml`
actually reads (see `04-devops/ENVIRONMENT_VARIABLES.md` for the full, corrected table). At
minimum for local dev you additionally need: `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET` (if testing
Gmail sync), and nothing else strictly required — `JWT_SECRET` auto-generates and persists to a
gitignored `gmail.env` if left unset.

### 3. Run the backend

```bash
cd backend
mvn spring-boot:run
```

Default profile is `dev` (`SPRING_PROFILES_ACTIVE` unset → `dev`). Server starts on `:8080`.

### 4. Verify

```bash
curl http://localhost:8080/actuator/health
```

API docs (Swagger UI): `http://localhost:8080/swagger-ui.html`.

### 5. Regenerate the TLS truststore (only if you see PKIX/SSL errors on outbound calls)

If your network intercepts TLS (corporate proxy), run:
```bash
backend/tools/regen-truststore.sh
```
This rebuilds `backend/truststore.p12` (JDK cacerts + your machine's trusted corporate roots).
Off a corporate network, this file/setup is not needed — the `-Djavax.net.ssl.*` JVM args can be
dropped from `pom.xml`'s `spring-boot-maven-plugin` config.

## Running tests

```bash
mvn test
```

Test dependencies: `spring-boot-starter-test`, `spring-security-test`, `h2database` (in-memory,
test scope only — production always runs on PostgreSQL).

## Local AI (Ollama) setup

```bash
ollama pull qwen2.5:7b
ollama serve   # if not already running as a service
```

Set `LLM_PROVIDER=none` to disable AI-dependent paths entirely (deterministic parsers keep
working) if you don't want to run Ollama locally.

## First things to read, in order

1. `BACKEND_STRUCTURE.md` — the layering pattern and per-domain package map.
2. `API_SPECIFICATION.md` — every endpoint, to know what's already there before adding a new one.
3. `FINANCIAL_ENGINE.md` — the calculation rules you must not violate (ledger-replay, net worth,
   XIRR, signal scoring).
4. `GMAIL_INGESTION_PIPELINE.md` — if you're touching anything email-related, including adding a
   new bank/broker parser (step-by-step guide included).
