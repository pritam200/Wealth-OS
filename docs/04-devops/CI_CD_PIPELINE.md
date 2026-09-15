# CI/CD Pipeline

## Current status: `[PLANNED / NOT IMPLEMENTED]`

Confirmed by repo-wide search: no `.github/workflows/*` belonging to this project (the only
`ci.yml` found anywhere in the repo tree is inside `frontend/node_modules/reusify/.github/
workflows/ci.yml` — a third-party npm package's own CI config, not project config), no
`Jenkinsfile`, no `.gitlab-ci.yml`.

**There is no automated build, test, or deploy gate today.** Every merge, build, and deploy is a
manual action. This document describes what exists to build *from* (the test suites and build
commands that would need to be wired into a pipeline), not an existing pipeline.

## What exists to build a pipeline around

### Backend build & test

```bash
cd backend
mvn clean test      # runs the full test suite (H2 in-memory DB, no external services needed)
mvn clean package    # produces the deployable jar
```

Test stack: `spring-boot-starter-test`, `spring-security-test`, `h2database` (test scope only —
production always runs on PostgreSQL, so a passing test suite does not guarantee Postgres-specific
SQL/dialect correctness).

### Frontend build

```bash
cd frontend
npm ci
npm run build   # Vite production build, outputs to dist/
```

No frontend test suite was identified during this audit — a CI pipeline for this repo would need
to add frontend testing before it could gate merges on frontend correctness.

### Docker images

Both `backend/Dockerfile` and `frontend/Dockerfile` exist and are multi-stage — see
`DEPLOYMENT_GUIDE.md` for their exact contents and the known Java-version mismatch between
`backend/Dockerfile` (Java 21 base image) and `pom.xml` (targets Java 25).

## Recommended minimum pipeline (not yet built)

A first CI pipeline for this repo would need, at minimum:

1. **On PR**: `mvn clean test` (backend), `npm run build` (frontend, catches TypeScript errors
   since Vite's build includes type-checking via `tsc`).
2. **Fix the Java version mismatch first** — before wiring Docker image builds into CI, resolve
   whether `backend/Dockerfile` should move to a JDK 25 base image or `pom.xml` should target a
   version the current Dockerfile can actually build.
3. **On merge to main**: build both Docker images, tag with commit SHA.
4. **Deploy step**: not designed in this codebase at all — no Kubernetes manifests, no cloud
   deployment config (Terraform/CDK/etc.) were found. `docker-compose.yml` is the only
   orchestration artifact present, and it's oriented at local/single-host development, not a
   production deployment target.

## Secrets management for a future pipeline

Whatever CI system is chosen would need to inject at minimum: `JWT_SECRET`, `DB_PASSWORD`,
`REDIS_PASSWORD`, `GEMINI_API_KEY`, `NEWS_API_KEY` (prod), `GMAIL_CLIENT_SECRET`,
`GMAIL_PUSH_TOKEN`, `PDF_PASSWORD_ENC_KEY` — see `ENVIRONMENT_VARIABLES.md` for the complete,
verified list of what the application actually reads.
