# Security Policy

## Reporting a vulnerability

Please **do not** open a public issue for security vulnerabilities.

Report privately via GitHub's
[security advisory form](https://github.com/bhumika-aga/RegulatoryApprovalSystem/security/advisories/new).
Include a description, reproduction steps, and the affected commit or version. You can expect an
initial response within a few days.

## Supported versions

This is a demonstration project; only the latest `main` is maintained.

## Security notes for operators

This project ships no built-in secrets. The following environment variables are **required**; the
application refuses to start if any is missing (or if the JWT key is too short):

- **`JWT_SECRET`** — Base64, decoding to at least 64 bytes for HS512 (`openssl rand -base64 64`).
- **`CAMUNDA_ADMIN_PASSWORD`** — guards the Camunda Cockpit login.
- **`AUTH_DEFAULT_PASSWORD`** (or per-user `AUTH_<USER>_PASSWORD`) — passwords for the seeded API users.
- The **H2 console** is disabled outside the `dev` profile; do not enable the `dev` profile in production.
- **`/actuator/health`** is the only publicly reachable actuator endpoint.
- The raw engine REST API (`/engine-rest`) answers only requests from the host itself (the in-process workers); others
  get 403.
- Tasklist and task completion through the Camunda webapp are disabled; tasks are completed through the API only.
- Swagger UI and `/v3/api-docs` are off unless `API_DOCS_ENABLED=true`.
- Failed logins (API and Camunda webapp) are rate-limited per client address; Camunda's per-user lock is off, so
  nobody can lock `admin` out of Cockpit. Behind a shared edge proxy, set **`CLIENT_IP_HEADER`** to the
  header carrying the real client address (`CF-Connecting-IP` on Render); never set it where clients connect directly.

See the README's _Security model_ section for the full picture.
