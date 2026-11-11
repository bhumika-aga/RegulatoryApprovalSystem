# Regulatory Approval System

[![CI](https://github.com/bhumika-aga/RegulatoryApprovalSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/bhumika-aga/RegulatoryApprovalSystem/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-green.svg)](https://spring.io/projects/spring-boot)
[![Camunda](https://img.shields.io/badge/Camunda-7.24.0-blue.svg)](https://camunda.com/)
[![H2](https://img.shields.io/badge/H2-In--Memory-blue.svg)](https://www.h2database.com/)

An enterprise-grade, BPMN-driven regulatory approval workflow built with **Spring Boot 3** and **Camunda 7**. It models
the kind of multi-stage approval process common in BFSI (Banking, Financial Services, Insurance) and healthcare:
a request is submitted, automatically risk-scored, reviewed by humans across several tiers, validated for compliance,
finally approved, and audited end-to-end — with SLA timers escalating any stage that stalls.

---

## 🚀 Overview

The system orchestrates the approval process with the **External Task Worker pattern**: the workflow engine owns the
_flow_, while all business logic runs in independent worker components that poll the engine for work. This keeps the
process model declarative and lets the business logic fail, retry, and scale independently of the engine.

### Core flow

```txt
Submit ─▶ Risk Scoring ─▶ Initial Review ─▶ Manager Approval ─▶ Compliance Check ─▶ Final Approval ─▶ Complete
         (automated)       (REVIEWER)         (MANAGER)          (automated)         (ADMIN /          (automated)
                              8h SLA            24h SLA               │               SENIOR_MANAGER)
                                │                  │                  ▼              8h SLA
                                ▼                  ▼            Manual Review           │
                           Escalate to        Escalate to       (COMPLIANCE, if         ▼
                            Manager          Senior Manager      ambiguous)          Escalate to
                                                                 48h SLA ─▶ Admin       Admin
```

Each human stage can **APPROVE**, **REJECT**, or branch (the reviewer may request more information; the manager may
escalate to a senior manager). Any rejection short-circuits to a single rejection path; every approval path converges on
the final approval. Completion (approved or rejected) is finalized by a worker and a notification is sent.

---

## 🧠 Logical Implementation & Concepts

### 1. BPMN orchestration (`regulatory-approval-process.bpmn`)

The process model defines the sequence of user tasks, service tasks, gateways, timers and end events.

- **User tasks** (`Initial Review`, `Manager Approval`, `Senior Manager Review`, `Compliance Manual Review`,
  `Final Approval`, `Provide Additional Information`) are assigned to **candidate groups** that map directly to JWT
  roles. They are driven through the REST API.
- **Service tasks** are implemented as **external tasks** (`camunda:type="external"`). The engine publishes work to a
  _topic_; a worker locks, executes and completes it. No business code runs inside the engine.
- **Exclusive gateways** route on process variables set when a task completes — e.g.
  `${reviewerDecision == 'APPROVED'}`, `${managerDecision == 'ESCALATE'}`, `${complianceResult == 'PASS'}`. The API
  accepts only decisions the next gateway can route (see _API Reference_).
- **Non-cancelling boundary timers** on the human tasks enforce SLAs: Initial Review 8 h → `MANAGER`, Manager Approval
  24 h → `SENIOR_MANAGER`, Compliance Manual Review 48 h → `ADMIN`, Final Approval 8 h → `ADMIN`. When a timer fires it
  spawns a parallel escalation branch (escalate + notify) **without** cancelling the still-pending task: the escalation
  target's role becomes a candidate for the task and any claim on it is released, so the target can pick it up (the
  original role still can too).

### Request lifecycle

Each request is also stored as a `RegulatoryRequest` row, which the workflow API returns. Its **status** is `PENDING`
(while it is being created), `IN_REVIEW`, then `APPROVED`, `REJECTED` or `TERMINATED`. Its **stage** follows the
process:

```txt
RISK_ASSESSMENT ─▶ INITIAL_REVIEW ─▶ MANAGER_APPROVAL ─▶ COMPLIANCE_CHECK ─▶ FINAL_APPROVAL ─▶ COMPLETED
                     ▲      │               │                   │
                     │      ▼               ▼                   ▼
          ADDITIONAL_INFORMATION   SENIOR_MANAGER_REVIEW   COMPLIANCE_REVIEW (manual)
```

A rejection at any step, or a failed compliance check, goes straight to `COMPLETED` with status `REJECTED` and a
rejection reason naming the step. The automated compliance result (`PASS` / `FAIL` / `REQUIRES_ADDITIONAL_INFO`, or
the manual `PASS` / `FAIL`) is returned as `complianceResult`.

### 2. External Task Workers

Five workers, one per topic, each implementing `ExternalTaskHandler` and subscribing on `ApplicationReadyEvent`:

| Worker                     | Topic                  | Responsibility                                                                 |
| :------------------------- | :--------------------- | :----------------------------------------------------------------------------- |
| `RiskScoringWorker`        | `risk-scoring`         | Scores 0–100 from request type + department, categorises (MINIMAL→CRITICAL)    |
| `ComplianceCheckWorker`    | `compliance-check`     | Automated regulatory validation → `PASS` / `FAIL` / `REQUIRES_ADDITIONAL_INFO` |
| `EscalationWorker`         | `escalation-handler`   | Hands the overdue task to the escalation target, flags the request, audits it  |
| `WorkflowCompletionWorker` | `workflow-completion`  | Finalises request status (`APPROVED` / `REJECTED`) and completion audit        |
| `NotificationWorker`       | `notification-service` | Sends approval / rejection / escalation notifications (logged by default)      |

The shared `ExternalTaskClient` is created with auto-fetching **disabled** and is started by
`ExternalTaskClientStarter` only after every worker has opened its subscription, so the very first poll already covers
all topics.

### 3. Resilience & fallbacks

Every worker wraps its logic in try/catch and degrades deliberately on failure. Each task gets three attempts with a
fixed back-off; the polling client itself uses an exponential back-off (`500ms × 2, capped at 10s`) when no work is
available. If the last attempt fails, each worker applies a fallback suited to how critical it is:

- **Risk scoring** — non-blocking: falls back to a default score of `50` / `MEDIUM` and completes, so review is never
  blocked by the scoring engine.
- **Compliance / Completion** — critical: no fallback. The task's retries run out and Camunda raises an **incident**,
  which an operator can retry from Cockpit, rather than silently passing.
- **Escalation / Notification** — best-effort: complete with an error flag so a failed notification never blocks the
  business process.

If a task's workflow has ended in the meantime (for example, it was terminated), the worker treats the vanished task as
done.

A request is updated by both API calls and workers, so two updates can collide: on the request row's optimistic-lock
`@Version`, or on Camunda's own rows (e.g. terminating a workflow while a worker is locking its task). The losing side
is re-run from a fresh read (up to three attempts); if it still collides, the API answers **409** and the client can
retry. Two people completing the same task at once get one success and a 409 or 404 — never a 500.

### 4. Security model

Authentication is **stateless JWT** (HS512). A token carries the subject (username), a `roles` claim and a `department`
claim — issued by `POST /api/v1/auth/token` after verifying a **username + password** against a configured user store
(`app.security.auth.users`; passwords are BCrypt-hashed at startup). **Roles and department come from that store, not
from the request**, so a caller cannot grant itself privileges. This store is a self-contained stand-in for an external
IdP in production. Access tokens last **24 hours**, refresh tokens **7 days**. `POST /api/v1/auth/refresh`
re-derives the current roles/department from the store rather than trusting the refresh token. Access and refresh
tokens are typed: the refresh endpoint accepts only a refresh token, and API calls only an access token.

Failed logins — to the API and to the Camunda webapp — are rate-limited per client address: after 10 failures within
15 minutes, further login attempts from that address get **429** with `Retry-After` until the window ends. (Counting per
address rather than per username means nobody can lock a user out by guessing their password. Behind a shared edge
proxy, set `CLIENT_IP_HEADER`; see _Deployment_.)

Seeded demo users (one per role — all share `AUTH_DEFAULT_PASSWORD`, overridable individually via
`AUTH_<ROLE>_PASSWORD`): `admin`, `reviewer`, `manager`, `senior.manager`, `compliance`, `auditor`.

- `JwtAuthenticationFilter` validates the token, builds the `UserPrincipal` / authorities, and makes the user and their
  roles (as groups) Camunda's identity for the duration of the request, clearing it afterwards.
- URL- and method-level authorization (`@PreAuthorize`) enforce role access; claiming or completing a task also requires
  the caller's role to be one of the task's candidate groups (or the task to be assigned to them).
- **Separation of duties** — nobody may claim or complete a review/approval task on a request they submitted, whatever
  their role (e.g. a manager approving their own submission, or an admin giving Final Approval to their own request).
  Such tasks are not listed for the submitter and attempts are refused with **403**. The one exception is _Provide
  Additional Information_, which is assigned to the submitter by design.
- Completing a task accepts only a decision and a comment; clients cannot set process variables (which drive routing
  and the automated checks).
- `JwtAuthenticationEntryPoint` returns a clean **401** when authentication is missing/invalid; `JwtAccessDeniedHandler`
  returns **403** when an authenticated user lacks the required role.
- **No built-in secrets.** `JWT_SECRET`, `CAMUNDA_ADMIN_PASSWORD` and the API users' passwords must be provided; the
  application refuses to start without them (and with a JWT key shorter than 64 bytes).

**Surface hardening.** Only what has to be public is public:

- `/actuator/health` is anonymous (platform health check); the other actuator endpoints (`info`, `metrics`) need an
  `ADMIN` token, and health details are shown only to authenticated callers.
- The Camunda webapp (`/camunda/**`) is protected by Camunda's own login — only the configured `admin` user exists.
  Tasklist and the webapp's task-completion endpoints are closed, so tasks are completed only through the API, with its
  role and separation-of-duties checks. Camunda's own lock on a user after failed logins is off — it let anyone keep
  `admin` out of Cockpit — and the webapp login is rate-limited per client address instead.
- The raw engine REST API (`/engine-rest/**`) is used only by the in-process external task workers, over `localhost`, and
  is refused (**403**) to any other host or to proxied requests. The workers do not log in, so failed logins against
  `admin` cannot stall them.
- Swagger UI and the OpenAPI description are off unless `API_DOCS_ENABLED=true` (on in the `dev` profile).
- The H2 console is disabled outside the `dev` profile.
- Line breaks in log messages are replaced, so a value from a request cannot forge extra log lines.

| Role             | Capabilities                                                                        |
| :--------------- | :---------------------------------------------------------------------------------- |
| `REVIEWER`       | Start workflows, Initial Review                                                     |
| `MANAGER`        | Start workflows, Manager Approval, escalated Initial Review, user & status queries  |
| `SENIOR_MANAGER` | Senior Manager Review, Final Approval, escalated Manager Approval, escalation views |
| `COMPLIANCE`     | Compliance Manual Review, audit access                                              |
| `AUDITOR`        | Read-only audit trail, user/status/escalation queries                               |
| `ADMIN`          | Start/terminate workflows, Final Approval, escalated compliance steps, every query  |

`MANAGER`, `SENIOR_MANAGER`, `COMPLIANCE`, `AUDITOR` and `ADMIN` are **oversight** roles: they can read any request and
its tasks. Anyone else sees only requests they submitted and tasks they can work on.

### 5. Audit trail

Every meaningful event is persisted to the `workflow_audit` table via `AuditService`. Records are written
**asynchronously**, so auditing never slows the request path, and only **after the surrounding transaction commits**, so
an action that is rolled back leaves no record. When the async pool is saturated the caller writes the record itself, so
no event is dropped:

- **Workflow events** — `WORKFLOW_STARTED` / `WORKFLOW_COMPLETED` / `WORKFLOW_TERMINATED` via execution listeners
  (`WORKFLOW_COMPLETED` once, when the process reaches its end event); the completion worker records the final
  `DECISION_MADE`.
- **Task events** — `TASK_CREATED` / `TASK_CLAIMED` / `TASK_COMPLETED` and `DECISION_MADE` via `TaskAuditListener`.
- **Compliance & SLA** — `COMPLIANCE_CHECK_PASSED/FAILED`, `SLA_BREACH`, `TASK_ESCALATED` from the workers.

The audit API supports lookups by process, task, user, event type, date range, SLA breaches, and event counts.

---

## 🏗️ Architecture

```txt
REST API (controllers)  ──▶  Service layer (WorkflowService, WorkflowTaskService, AuditService)
        │                              │
        │                              ▼
        │                     Camunda 7 engine  ◀── execution & task listeners (audit)
        │                              │
        ▼                              ▼  (external task topics)
   JWT security             External task workers (risk, compliance, escalation, completion, notification)
        │                              │
        └──────────────▶  JPA / H2 in-memory  ◀───────────┘
```

- **REST API layer** — Spring MVC controllers for Auth, Workflow, Tasks, Audit.
- **Service layer** — orchestration, JPA persistence, security context access.
- **Engine layer** — embedded Camunda 7 process engine (job executor, history, metrics).
- **Worker layer** — independent external-task clients polling the engine REST API.
- **Persistence layer** — H2 in-memory database; schema managed by Hibernate (`ddl-auto: update`).

### Project structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/          # Spring, Camunda, async, OpenAPI configuration; local-only and login-rate-limit filters
├── controller/      # REST endpoints (auth, workflow, tasks, audit)
├── service/         # Business orchestration, request updates, concurrency retries
├── worker/          # External task workers + client starter
├── listener/        # Camunda execution & task listeners (audit)
├── security/        # JWT provider and filter, login rate limiting, user store, entry point, access-denied handler
├── model/           # JPA entities & enums (incl. ReviewStep: each user task's variables and decisions)
├── dto/             # Request/response payloads
├── repository/      # Spring Data JPA repositories
└── exception/       # Domain exceptions + global handler
src/main/resources/
├── application.yml
├── application-dev.yml   # dev profile: H2 console, Swagger, SQL logging
└── bpmn/                 # Process definition (.bpmn)
```

---

## 📡 API Reference

Base path: `/api/v1`. All endpoints except auth require a `Bearer` token. (Outside it, `/actuator/health` is public.)

| Method & Path                               | Role(s)                        | Description                            |
| :------------------------------------------ | :----------------------------- | :------------------------------------- |
| `POST /auth/token`                          | public (username + password)   | Authenticate → access + refresh tokens |
| `POST /auth/refresh`                        | public (`X-Refresh-Token`)     | Rotate tokens                          |
| `POST /workflow/start`                      | REVIEWER, MANAGER, ADMIN       | Start an approval workflow             |
| `GET  /workflow/status/{processInstanceId}` | submitter or oversight         | Current workflow state                 |
| `GET  /workflow/{requestId}`                | submitter or oversight         | Workflow by request UUID               |
| `GET  /workflow/my-requests`                | authenticated                  | Caller's submissions                   |
| `GET  /workflow/user/{userId}`              | MANAGER, ADMIN, AUDITOR        | Submissions by a user                  |
| `GET  /workflow/by-status/{status}`         | MANAGER, ADMIN, AUDITOR        | Filter by status                       |
| `GET  /workflow/escalated`                  | SENIOR_MANAGER, ADMIN, AUDITOR | Escalated workflows                    |
| `DELETE /workflow/{processInstanceId}`      | ADMIN                          | Terminate a workflow                   |
| `GET  /tasks`                               | authenticated                  | Tasks assigned to / available to me    |
| `GET  /tasks/process/{processInstanceId}`   | submitter or oversight         | Active tasks for an instance           |
| `GET  /tasks/{taskId}`                      | candidate, assignee, oversight | Task details                           |
| `POST /tasks/{taskId}/claim` · `/unclaim`   | candidate (not the submitter)  | Claim / release a task                 |
| `POST /tasks/{taskId}/complete`             | candidate (not the submitter)  | Complete with a decision               |
| `GET  /audit/**`                            | AUDITOR, ADMIN, COMPLIANCE     | Audit trail queries                    |
| `GET  /actuator/health`                     | public                         | Health probe (outside `/api/v1`)       |
| `GET  /actuator/info` · `/metrics`          | ADMIN                          | Build info, metrics                    |

`complete` accepts a `decision` and an optional `comment`; any other properties are ignored. The decision must be one
the task's next step can route, otherwise the call is refused with **400**:

| Task                           | Decisions                            |
| :----------------------------- | :----------------------------------- |
| Initial Review                 | `APPROVED`, `REJECTED`, `NEEDS_INFO` |
| Provide Additional Information | `APPROVED` (submit the information)  |
| Manager Approval               | `APPROVED`, `REJECTED`, `ESCALATE`   |
| Senior Manager Review          | `APPROVED`, `REJECTED`               |
| Compliance Manual Review       | `PASS`, `FAIL`                       |
| Final Approval                 | `APPROVED`, `REJECTED`               |

Errors use the same JSON envelope (`success: false`, `message`, `path`) and conventional status codes: **400** invalid
input (validation, malformed JSON, an unroutable decision), **401** missing or invalid token, **403** wrong role or
separation of duties, **404** unknown resource, **405** wrong method, **409** conflict (task claimed by someone else,
or a concurrent update that outlasted the retries), **415** unsupported content type, **429** too many failed logins.

### Testing the API

A ready-to-run **Postman collection** is included: [
`RegulatoryApprovalSystem.postman_collection.json`](RegulatoryApprovalSystem.postman_collection.json).
It walks a request through the full approval (Initial Review → Manager Approval → automated compliance → Final
Approval), plus a rejection, the request-more-information loop, a termination, audit queries, and security checks
(role and separation-of-duties refusals, token types, unroutable decisions, admin-only metrics, the closed Tasklist and
engine REST API, 4xx client errors). Every request asserts its
status code and key fields, and test scripts capture tokens and ids into collection variables, so it runs top-to-bottom
without copy/paste. Steps that wait for a background worker retry themselves for up to ~30 seconds. Run it in the
Collection Runner, or from the command line:

```bash
postman collection run RegulatoryApprovalSystem.postman_collection.json \
  --env-var baseUrl=http://localhost:8080 --env-var authPassword="$AUTH_DEFAULT_PASSWORD"
```

The `authPassword` collection variable must be set to the API users' password (`AUTH_DEFAULT_PASSWORD`).

The collection creates workflows, so run it against a local or test instance, not one holding real data. It never
sends a wrong password (failed logins are rate-limited). To run it against Render, use
`--env-var baseUrl=https://<your-service>.onrender.com`.

Interactive docs are also available via Swagger UI at `/swagger-ui.html` when API docs are enabled (`dev` profile or
`API_DOCS_ENABLED=true`).

### Automated tests

`mvn verify` runs the test suite:

- **`AuthUserServiceTest`** — unit tests for credential verification (BCrypt match, unknown user, case-insensitive
  lookup).
- **`WorkerFailureHandlingTest`**, **`ReviewStepRoutingTest`** — worker retries and last-attempt fallbacks, the stage
  set by risk scoring, and which decisions each task accepts and the stage each leads to.
- **`ConcurrencyRetryTest`**, **`CompleteTaskRaceTest`**, **`ConcurrentUpdateResponseTest`**, **`AuditServiceTest`** —
  retrying conflicting updates, losing a claim race (409), mapping conflicts to 409, and writing audit records only
  after the transaction commits.
- **`JwtTokenProviderTest`**, **`JwtAuthenticationFilterTest`**, **`LoginAttemptLimiterTest`**,
  **`ClientAddressResolverTest`**, **`LocalOnlyFilterTest`** — key validation and token types, clearing the Camunda
  identity after each request, login rate limiting and client addresses, and the engine REST API's local-only access.
- **Full-stack tests over real HTTP** (they share one running application; see `IntegrationTestBase`):
  - **`AuthenticationIntegrationTest`** — logins (400/401/200), server-controlled roles, token types and refresh, and
    API and Cockpit login rate limiting.
  - **`AccessControlIntegrationTest`** — a role can only complete tasks its candidate group owns, separation of duties,
    read scoping (a user cannot read another user's workflow/tasks without an oversight role), and that clients cannot
    set process variables.
  - **`WorkflowIntegrationTest`** — decision validation, request stages, the audit trail, compliance results and SLA
    escalation.
  - **`ConcurrencyIntegrationTest`** — concurrent updates by API calls and workers are retried and never fail.
  - **`ExposedSurfaceIntegrationTest`** — the engine REST API, Tasklist, API docs, actuator, client errors (4xx, not 500) and that request values cannot forge log lines.

The full-stack tests run on a random free port, so nothing needs to be free beforehand. Every push and pull request
runs the suite, a Docker image build and CodeQL via GitHub Actions (`.github/workflows/`).

---

## 🛠️ Technology Stack

- **Framework**: Spring Boot 3.5.16 (Java 21)
- **Workflow**: Camunda BPM 7.24 (engine, REST, webapp, external-task client)
- **Security**: Spring Security + JJWT (HS512)
- **API docs**: SpringDoc OpenAPI (Swagger UI)
- **Persistence**: Spring Data JPA + H2 (in-memory)
- **Tooling**: Lombok

---

## 🚦 Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+

### Environment variables

All three are **required** — there are no built-in defaults, and the application refuses to start without them:

```bash
export JWT_SECRET=$(openssl rand -base64 64)        # Base64, must decode to ≥ 64 bytes for HS512
export CAMUNDA_ADMIN_PASSWORD='choose-a-password'   # Camunda webapp (Cockpit) login
export AUTH_DEFAULT_PASSWORD='choose-a-password'    # password for the seeded API users (admin, reviewer, manager, ...)
```

Optional:

| Variable                                                                                                                                                      | Effect                                                                                          |
| :------------------------------------------------------------------------------------------------------------------------------------------------------------ | :---------------------------------------------------------------------------------------------- |
| `AUTH_ADMIN_PASSWORD`, `AUTH_REVIEWER_PASSWORD`, `AUTH_MANAGER_PASSWORD`, `AUTH_SENIOR_MANAGER_PASSWORD`, `AUTH_COMPLIANCE_PASSWORD`, `AUTH_AUDITOR_PASSWORD` | A user's own password instead of `AUTH_DEFAULT_PASSWORD`                                        |
| `API_DOCS_ENABLED=true`                                                                                                                                       | Serve Swagger UI and `/v3/api-docs` (on in the `dev` profile)                                   |
| `CLIENT_IP_HEADER`                                                                                                                                            | Header with the real client address behind a shared proxy (`CF-Connecting-IP` on Render)        |
| `APP_CORS_ALLOWED_ORIGINS`                                                                                                                                    | Comma-separated browser origins allowed to call the API (default: `localhost:3000` and `:8080`) |

### Run locally

```bash
mvn clean package
java -jar target/regulatory-approval-system-1.0.0.jar   # with the variables above exported
# or, for development (enables the H2 console — see below):
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

- **Swagger UI**: `http://localhost:8080/swagger-ui.html` — `dev` profile (or `API_DOCS_ENABLED=true`) only
- **Camunda Webapp**: `http://localhost:8080/camunda` (login `admin` / `${CAMUNDA_ADMIN_PASSWORD}`)
- **H2 Console**: `http://localhost:8080/h2-console` — **`dev` profile only** (JDBC `jdbc:h2:mem:regulatory_db`, user
  `sa`).
  It is disabled in the default/production configuration.
- **Health**: `http://localhost:8080/actuator/health` (the only public actuator endpoint; `info` and `metrics` need an
  `ADMIN` token)

---

## 🐳 Deployment

A multi-stage `Dockerfile` (Maven build + slim JRE runtime, non-root user, health check, JVM tuned for a 512 MB
container) is provided.

```bash
# Docker Compose (requires JWT_SECRET, CAMUNDA_ADMIN_PASSWORD and AUTH_DEFAULT_PASSWORD in the environment)
docker compose up -d
```

### Render

The project is pre-configured for **Render** via `render.yaml` (Docker runtime, free plan, health check on
`/actuator/health`). Create it with **New → Blueprint**, pointing at this repository; Render then asks for the three
secrets (`JWT_SECRET`, `CAMUNDA_ADMIN_PASSWORD`, `AUTH_DEFAULT_PASSWORD`). Render only asks when a Blueprint is first
created, so for an existing service add any that are missing under **Environment** before deploying — the application
will not start without them. Pushes to `main` then redeploy automatically.

On the free plan the service sleeps after 15 minutes without traffic (the first request afterwards takes a minute or
so to wake it), and because the database is **H2 in-memory**, every restart, redeploy or sleep starts with an empty
database. That suits a demo; for lasting data, point `spring.datasource` at an external database (and add its JDBC
driver).

Render is fronted by Cloudflare, so every request reaches the app from a shared proxy. `render.yaml` therefore sets
`CLIENT_IP_HEADER=CF-Connecting-IP`, the header in which Cloudflare passes the real client address, so failed logins are
counted per client rather than per proxy. (If the service was not created from the Blueprint, add it under
**Environment**.) Leave it unset anywhere clients connect directly, as they could then forge the header.

Once deployed: the API is at `https://<your-service>.onrender.com/api/v1`, Cockpit at `/camunda` (login `admin` /
`CAMUNDA_ADMIN_PASSWORD`), and the Postman collection runs against it with `--env-var baseUrl=https://<your-service>.onrender.com`.
