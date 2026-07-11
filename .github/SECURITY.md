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

This project ships safe-by-default configuration but relies on a few environment variables being set
correctly in any real deployment:

- **`JWT_SECRET`** — Base64, decoding to at least 64 bytes for HS512. The bundled fallback is for local
  development only; always override it.
- **`CAMUNDA_ADMIN_PASSWORD`** — guards the Camunda Cockpit login and worker access to the engine REST API.
- **`AUTH_DEFAULT_PASSWORD`** (and per-user `AUTH_<ROLE>_PASSWORD`) — passwords for the seeded API users.
- The **H2 console** is disabled outside the `dev` profile; do not enable the `dev` profile in production.
- **`/actuator/health`** is the only publicly reachable actuator endpoint; the raw engine REST API
  (`/engine-rest`) requires HTTP Basic authentication.

See the README's *Security model* section for the full picture.
