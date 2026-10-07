# Admin Console API

Base path `/api/admin`. Every call needs the normal `Authorization: Bearer <jwt>` of an allowlisted administrator, from an allowlisted IP. Changes (anything but GET) must also send `X-Confirm-Environment: <the server's environment>`, mandatory in PRODUCTION. Denials are generic JSON `{"error", "requestId"}` (401 not signed in, 403 denied, 412 environment not confirmed, 429 rate limit, 503 lockdown). `403 MFA_REQUIRED` means "verify an authenticator code, then retry".

Roles: **READ_ONLY** (view) < **DEVELOPER** (+ edit data, flags, rotate secrets) < **ADMIN** (+ manage access, lockdown, approvals). 🔐 = needs a fresh authenticator code when MFA is required (always in PRODUCTION unless `ADMIN_MFA_REQUIRED=false`).

| Method + path | Role | Purpose |
|---|---|---|
| GET `/me` | any | role, environment, client IP as seen (audited as console opened) |
| GET `/dashboard` | any | 24h request/deny counts, allowlist sizes, lockdown, MFA |
| GET/POST `/ip-allowlist`, PUT/DELETE `/ip-allowlist/{id}` | view / ADMIN | CIDR allowlist for this environment; self-lockout refused; `reason` required in PRODUCTION |
| GET/POST `/email-allowlist`, PUT/DELETE `/email-allowlist/{id}` | view / ADMIN | `{kind: EMAIL\|DOMAIN, value, role, expiresAt, reason}`; a domain is never ADMIN |
| GET `/request-logs` | any | filters `from,to,ip,email,path,status,decision,page,size` |
| GET `/audit-logs`, GET `/audit-logs/verify` | any | append-only log; verify recomputes the hash chain |
| GET `/data/tables` | any | tables, columns, which are editable / sensitive |
| GET `/data/{table}?q=&page=&size=` | any | search (parameterised, allowlisted columns only) |
| GET `/data/{table}/{id}` | any | one record |
| POST `/data/{table}/{id}/preview` `{changes}` | DEVELOPER | before/after, whether approval is needed |
| PUT `/data/{table}/{id}` `{changes, reason}` 🔐 | DEVELOPER | applies ordinary fields; sensitive fields become a change request |
| GET `/data-changes`; POST `/data-changes/{id}/approve` 🔐, `/reject` | view / ADMIN | a *different* administrator decides |
| GET `/config` | any | non-secret values; secrets as `Configured · ********abcd` only; flags |
| PUT `/config/secrets/{name}/rotate` `{value, reason}` 🔐 | DEVELOPER | sends the value to the secret manager only; 501 if none connected |
| PUT `/config/flags/{name}` `{enabled, reason}` 🔐 | DEVELOPER | registered flags only |
| GET `/deployment` | any | environment, version, commit, start time, profiles |
| GET `/security/sessions` | any | accounts with live refresh tokens |
| GET/POST `/security/lockdown`; POST `/security/lockdown/release` | view / ADMIN | release only by a bootstrap admin on a bootstrap IP |
| POST `/security/ip-allowlist/{id}/disable`, `/security/accounts/{email}/disable` | ADMIN | emergency switches; the second also revokes sessions |
| GET `/mfa`; POST `/mfa/enroll`, `/mfa/verify` `{code}`; POST `/mfa/reset` 🔐 | any / ADMIN | TOTP (RFC 6238); the secret is shown once, stored encrypted |

## Data console policy
Defined in `admin/data/TablePolicies.java`: `users` (name editable; `enabled` sensitive → second administrator; password hash never selectable) and `portfolios` (name, description editable). Adding a table means adding it there and reviewing the diff.

## Customer account deletion (not an admin API)
`DELETE /api/auth/account {password}` removes the signed-in customer and all their data (tables found from the database's `user_id` columns and foreign keys). Console administrators listed in `ADMIN_EMAILS` are refused.

## Deployment
`deploy/.env`: `APP_ENVIRONMENT=PRODUCTION`, `ADMIN_EMAILS`, `ADMIN_BOOTSTRAP_IPS`, `ADMIN_TRUSTED_PROXIES` (compose defaults to `172.16.0.0/12`), optional `ADMIN_MFA_REQUIRED`, `ADMIN_SECRET_STORE_COMMAND` (command receiving the secret name as its last argument and the value on stdin, run without a shell). Re-run `scripts/secrets.sh encrypt`. Apply `database/admin_audit_immutability.sql` once. Rollback: drop the `admin_*` tables.
