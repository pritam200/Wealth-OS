# Deployment Guide

## ⚠️ Read this first: Java version mismatch

`pom.xml` declares `java.version`/`maven.compiler.release=25`. `backend/Dockerfile` builds and
runs on `eclipse-temurin:21-*-alpine`. **Verify which is authoritative before deploying** — as
audited, the Maven build inside that container image would fail against the current `pom.xml`
unless the Dockerfile has since been updated to a JDK 25 base image. This is not a hypothetical
edge case; it's a directly observed discrepancy between two files in the repo.

## Docker images (verified contents)

### `backend/Dockerfile` (multi-stage)

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=build /app/target/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
```

Runs as non-root `appuser`. JVM flags scope container memory
(`UseContainerSupport`/`MaxRAMPercentage=75.0`). **No `truststore.p12` is copied into the image**
— the corporate-proxy TLS JVM args in `pom.xml`'s `spring-boot-maven-plugin` are a local
build-time concern, not carried into the container's runtime `ENTRYPOINT`. If the container's
egress also passes through a TLS-inspecting proxy, this would need to be added.

### `frontend/Dockerfile` (multi-stage)

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL=http://localhost:8080
ENV VITE_API_URL=$VITE_API_URL
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

`VITE_API_URL` is a **build-time ARG** — baked into the static JS bundle. Changing the backend
URL for an already-built image requires a rebuild with a new `--build-arg`, not an env var change
on the running container.

## Docker Compose (local/single-host deployment)

```bash
docker compose up -d
```

See `DEVOPS_ONBOARDING.md` for the full service table. This is the only orchestration artifact in
the repo — **no Kubernetes manifests, Helm charts, or cloud IaC (Terraform/CDK/Pulumi) were
found**. A production deployment target beyond a single Docker host is `[PLANNED / NOT
IMPLEMENTED]`.

## Database migrations

`spring.jpa.hibernate.ddl-auto`:
- `dev`: `update` — Hibernate auto-migrates the schema on startup.
- `prod`: `validate` — **Hibernate will refuse to start if the schema doesn't already match the
  entity model.** Production schema must be migrated by some other means before deploying new
  entity changes — no Flyway/Liquibase migration tool was found in `pom.xml`'s dependencies, and
  `database/schema.sql`/`seed.sql` are only wired into the Postgres container's *first-boot*
  init scripts (`docker-entrypoint-initdb.d`), not a repeatable migration tool.

**Practical implication**: deploying a change that adds/modifies an entity to a prod-profile
environment requires manually applying the equivalent DDL to the production database before (or
as part of) the deploy — there is currently no automated migration step.

## Environment configuration for prod

See `ENVIRONMENT_VARIABLES.md` for the complete matrix, including which variables have **no
default in the `prod` YAML profile document at all** (several `app.sync.*`, `app.gmail.push.*`,
`app.llm.*` keys) — these silently fall back to whatever default is hardcoded at the Java
`@Value` annotation site, not to anything in `application.yml`'s `prod` block. Confirm each of
these resolves to an intentional value before a production deploy, since the YAML file alone does
not make this visible.

## Health check

`GET /actuator/health` — used by Docker Compose's `backend` healthcheck
(`wget -q --spider http://localhost:8080/actuator/health`, every 30s, 5 retries) and is
`permitAll` in `SecurityConfig` (no auth required).
