# Developer / Admin Control Console

Status: **Phases 1-4 implemented** (access control, allowlists, monitoring, immutable audit, lockdown; policy-driven data console with second-administrator approval and TOTP step-up; configuration, flags, write-only secrets and rotation hook; React console at `/admin`). Endpoint reference: `ADMIN_API.md`. Section 7 records the original plan; the gaps in section 10 still apply except MFA, which now exists. This document is the architecture, security model and threat model for all phases.

## 1. What already exists and is reused

| Existing | Reused for |
|---|---|
| `JwtAuthFilter` / `JwtService` / `UserDetailsServiceImpl` (stateless JWT, bcrypt-12) | Authentication. **No second login system.** An admin is a normal `User` that is additionally authorised below. |
| `RefreshToken` + `revokeAllUserTokens` | Revoking an admin's sessions. |
| `AdminAccess` / `app.admin.emails` (`ADMIN_EMAILS`) | Bootstrap: these emails are always `ADMIN`, so the first administrator exists without a database row (and cannot be locked out by the database). |
| `SecurityConfig` (`/api/admin/**` already required `ROLE_ADMIN`) | Filter-chain placement. |
| JPA `ddl-auto: update`, PostgreSQL/H2 | Schema (no Flyway in the project; SQL files are provided in `database/`). |
| Environment variables → `application.yml` | Configuration. Secrets come from the environment / secret manager, never from the database. |

Not present and therefore added: IP allowlisting, trusted-proxy client-IP resolution, an admin email allowlist with roles, request monitoring, a tamper-evident audit log, lockdown. **Not yet present: MFA/SSO (Phase 2, see gaps).**

## 2. Proposed architecture

```
Request
  → TrustedProxy / ClientIpResolver         (real client IP, or "unknown" → deny)
  → AdminAccessFilter  (only /api/admin/**)
       1. lockdown?                          → 503 (only bootstrap-admin on a bootstrap IP may release)
       2. IP allowlist (this environment)    → 403 ADMIN_IP_DENIED
       3. authenticated (JWT)?               → 401
       4. email allowlist entry active, unexpired, or bootstrap admin → role   → 403 ADMIN_NOT_AUTHORISED
       5. request context (request id, ip, email, role, environment) bound to the request
  → Controller method: AdminAuthz.require(Permission)   (each endpoint decides again, independently)
  → production guard: mutating call must carry X-Confirm-Environment equal to the server's environment
  → validation → operation
  → AdminAuditService.record(...)            (append-only, hash-chained)
  → finally: AdminRequestLog row (path only, never query string, headers, bodies or tokens)
```

Package `com.marketai.admin`: `domain` (entities), `repo`, `net` (CIDR, client-IP resolution), `security` (filter, context, authorisation), `service`, `api`.

## 3. Roles and permissions

| Permission | READ_ONLY | DEVELOPER | ADMIN |
|---|---|---|---|
| View dashboard, allowlists, request/audit logs | yes | yes | yes |
| View production data (Phase 2), config metadata (Phase 3) | yes | yes | yes |
| Edit approved production fields, change config, rotate secrets | no | yes | yes |
| Manage IP/email allowlists, roles, lockdown, revoke sessions | no | no | yes |

An email allowlist entry sets the role. Admin emails in `ADMIN_EMAILS` are always `ADMIN`.

## 4. Database schema (Phase 1)

`admin_ip_allowlist(id, cidr, description, environment, status, created_by, created_at, updated_by, updated_at, expires_at)`
`admin_email_allowlist(id, email_or_domain, kind[EMAIL|DOMAIN], role, status, created_by, created_at, updated_by, updated_at, expires_at)`
`admin_audit_events(id, occurred_at, actor_email, actor_ip, request_id, action, target_type, target_id, operation, before_value, after_value, reason, environment, prev_hash, event_hash)`: append-only; `event_hash = SHA-256(prev_hash ‖ canonical fields)`; the entity is `@Immutable`, the repository has no update/delete, and `database/admin_audit_immutability.sql` adds PostgreSQL triggers that reject UPDATE/DELETE.
`admin_request_log(id, occurred_at, request_id, source_ip, actor_email, method, path, status, duration_ms, environment, decision[ALLOW|DENY], deny_reason, user_agent)`
`admin_lockdown(id=1, engaged, engaged_by, engaged_at, reason)`

Phase 2-3 add: `admin_table_policy` (not a table: policy lives in code, see §7), `admin_change_request(id, table, record_id, field changes, status, requested_by, approved_by, reason, ticket)`, `admin_config_meta` (names, categories, last-rotated: **never values**).

## 5. API (Phase 1) — all under `/api/admin`, all behind the pipeline above

| Method + path | Permission | Notes |
|---|---|---|
| `GET /me` | any admin | role, environment, client IP as seen |
| `GET/POST /ip-allowlist`, `PUT/DELETE /ip-allowlist/{id}` | view / MANAGE_ACCESS | CIDR validated; refuses a change that would lock the caller out |
| `GET/POST /email-allowlist`, `PUT/DELETE /email-allowlist/{id}` | view / MANAGE_ACCESS | cannot remove/deactivate your own entry or the last ADMIN |
| `GET /request-logs` | view | filters: from, to, ip, email, path, status, decision |
| `GET /audit-logs`, `GET /audit-logs/verify` | view | `verify` walks the hash chain |
| `GET /security/lockdown`, `POST /security/lockdown`, `POST /security/lockdown/release` | view / MANAGE_ACCESS | audited |
| `POST /security/ip-allowlist/{id}/disable` | MANAGE_ACCESS | emergency disable of one IP |
| `POST /security/accounts/{email}/disable` | MANAGE_ACCESS | deactivates the allowlist entry **and** revokes that user's refresh tokens |

Phase 2+: `/data/tables`, `/data/{table}` (search), `/data/{table}/{id}` (view/update), `/config/*`, `/deployment/*`.

## 6. Security model

* **Fail closed.** Unknown client IP, empty allowlist, expired entry, inactive entry, lockdown → deny. With nothing configured nobody gets in; the first administrator comes from `ADMIN_EMAILS` + `ADMIN_BOOTSTRAP_IPS` (environment, not database).
* **Client IP.** `X-Forwarded-For` is honoured only when the TCP peer is inside `app.admin.trusted-proxies` (CIDR list, empty by default = the header is ignored). The header is read right-to-left, skipping trusted proxies, and the first untrusted address is the client. A malformed header, or a chain made only of trusted proxies, gives "unknown" → deny. Spring's `server.forward-headers-strategy` stays `none`; the resolver is the single source of truth.
* **Authentication is the existing JWT.** The admin pipeline adds authorisation on top; it does not mint its own sessions.
* **Environment isolation.** `app.admin.environment` (`APP_ENVIRONMENT`: DEV|STAGE|PRODUCTION, default DEV). IP entries apply only to their environment. In PRODUCTION every mutating admin call must send `X-Confirm-Environment: PRODUCTION`, so a UI pointed at the wrong server cannot change it.
* **No secrets in the database, logs or audit.** Request logs hold path (no query string), method, status, timing, IP, email, user agent; never headers or bodies. Audit before/after values pass through a redactor that masks values of keys that look like secrets.
* **Self-lockout protection.** A change that removes the caller's own access (their IP, their email entry, the last ADMIN) is refused.
* **No generic SQL console. No hidden accounts. No hardcoded credentials.**
* Existing controls kept: HTTPS at Caddy, strict CORS, bcrypt, stateless JWT.

## 7. Planned phases

* **Phase 2 — Production data console + MFA.** Table policy in code: allowed tables, searchable columns, viewable columns, **editable fields** (everything else read-only), sensitive tables excluded (users' password hashes, tokens, encrypted payloads). Reads use parameterised JPA criteria built from the policy, never concatenated SQL. Updates: field whitelist → before/after preview → reason (required in PRODUCTION) → confirmation → audit; sensitive fields need a second approver (`admin_change_request`). **TOTP MFA** (RFC 6238) as a step-up for admin sessions; OIDC/SSO if you have an identity provider.
* **Phase 3 — Configuration & secrets.** Config registry with typed, non-secret values from environment / the secret manager. Secrets are **write-only**: the console shows `Configured: YES · last rotated · ********abcd` and offers *rotate*; rotation goes to the secret manager (AWS Secrets Manager / SSM / Vault via a `SecretStore` interface; the Phase-3 default writes to the encrypted deploy bundle path only if no manager is configured). There is no "show secret".
* **Phase 4 — UI.** React console (`/admin`, lazy-loaded, separate shell) with the navigation in §8, a permanent environment banner ("PRODUCTION — you are modifying production"), plus deployment info (version, git commit, deploy time from build metadata).

## 8. UI structure

Dashboard · Access Control (Admin users, Email allowlist, IP allowlist) · Production Data (Tables, Search, Data changes) · Monitoring (Request logs, Security events, Audit logs) · Configuration (Application, Feature flags, Integrations, Secrets metadata) · Deployment (Version, Environment, History) · Security (Active sessions, Emergency lockdown, Settings).

## 9. Threat model (summary)

| Threat | Control |
|---|---|
| Spoofed `X-Forwarded-For` to pass the IP allowlist | Header trusted only from configured proxies; right-to-left walk; malformed → deny |
| Stolen admin JWT used from another network | IP allowlist applies to every admin request; short access-token life; refresh tokens revocable |
| Compromised developer account | Email allowlist + role; emergency account disable revokes refresh tokens; MFA step-up (Phase 2) |
| Admin account that is a normal user | Normal users have no allowlist entry → 403 |
| Insider edits data silently | Immutable, hash-chained audit with before/after, reason, request id; DB triggers forbid UPDATE/DELETE; `verify` detects tampering |
| Log injection / secret leakage via logs | Path only, no query/headers/bodies; redaction of secret-looking keys; control characters stripped |
| Admin locks everyone out | Bootstrap admin from environment; self-lockout guard; lockdown release only by a bootstrap admin on a bootstrap IP |
| DEV console pointed at PRODUCTION | `X-Confirm-Environment` must equal the server's environment on every mutation; IP entries are per-environment |
| SQL injection / arbitrary SQL | No SQL console; Phase-2 reads/writes built from a code allowlist with bound parameters |
| Brute force on admin login | Existing login unchanged for now; per-IP rate limit on `/api/admin/**` (Phase 1) and login rate limiting (Phase 2) |
| Backdoor / hardcoded credentials | None; bootstrap values come from environment variables |

## 10. Known gaps after Phase 1

* **No MFA/SSO yet.** Until Phase 2, admin access = existing password login + email allowlist + IP allowlist. Treat the IP allowlist as mandatory in production.
* Access tokens are stateless; account disable stops new admin requests immediately (allowlist is checked per request) and blocks refresh, but an already-issued token remains valid for non-admin endpoints until it expires.
* The audit hash chain is tamper-*evident*, not tamper-proof: someone with full database control can rewrite the whole chain. Ship audit events to an external write-once store for stronger guarantees.
* The request log and audit writer assume a single application instance (chain head is serialised in-process).

## 11. Deployment

Verification: `mvn test` (1161 tests, ~80 of them for this module and account deletion: CIDR parsing, trusted-proxy IP resolution, the filter pipeline, role/allowlist rules, self-lockout, lockdown, audit-chain tamper detection, redaction, PRODUCTION guard). Not yet exercised against a live PostgreSQL or the real Caddy proxy: check `GET /api/admin/me` from an allowed address after deploying and confirm `clientIp` is your real address.

Set in `deploy/.env` (then re-encrypt the bundle): `APP_ENVIRONMENT=PRODUCTION`, `ADMIN_EMAILS=you@example.com`, `ADMIN_BOOTSTRAP_IPS=203.0.113.10`, `ADMIN_TRUSTED_PROXIES=<Caddy/Docker network CIDR, e.g. 172.16.0.0/12>`. Apply `database/admin_audit_immutability.sql` once as the database owner. Rollback: drop the `admin_*` tables.
