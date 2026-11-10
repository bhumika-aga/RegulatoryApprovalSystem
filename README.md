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
  roles. They are driven through the REST API. Each also has a Camunda Form (`*.form` under `resources/bpmn/`) whose
  fields match the process variables; with Tasklist closed, the forms document each step's inputs rather than being
  rendered.
- **Service tasks** are implemented as **external tasks** (`camunda:type="external"`). The engine publishes work to a
  _topic_; a worker locks, executes and completes it. No business code runs inside the engine.
- **Exclusive gateways** route on process variables set when a task completes — e.g.
  `${reviewerDecision == 'APPROVED'}`,
  `${managerDecision == 'ESCALATE'}`, `${complianceResult == 'PASS'}`.
- **Non-cancelling boundary timers** on the human tasks enforce SLAs (`PT8H`, `PT24H`, `PT48H`, `PT8H`). When a timer
  fires it spawns a parallel escalation branch (escalate + notify) **without** cancelling the still-pending task: the
  escalation target's role becomes a candidate for the task and any claim on it is released, so the target can pick it
  up (the original role still can too).

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

### 4. Security model

Authentication is **stateless JWT** (HS512). A token carries the subject (username), a `roles` claim and a `department`
claim — issued by `POST /api/v1/auth/token` after verifying a **username + password** against a configured user store
(`app.security.auth.users`; passwords are BCrypt-hashed at startup). **Roles and department come from that store, not
from the request**, so a caller cannot grant itself privileges. This store is a self-contained stand-in for an external
IdP in production. `POST /api/v1/auth/refresh` re-derives the current roles/department from the store rather than
trusting the refresh token. Access and refresh tokens are typed: the refresh endpoint accepts only a refresh token, and
API calls only an access token.

Failed logins — to the API and to the Camunda webapp — are rate-limited per client address: after 10 failures within
15 minutes, further login attempts from that address get **429** with `Retry-After` until the window ends. (Counting per address rather than per username means nobody can
lock a user out by guessing their password. Behind a shared edge proxy, set `CLIENT_IP_HEADER`; see _Deployment_.)

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

| Role             | Capabilities                                            |
| :--------------- | :------------------------------------------------------ |
| `REVIEWER`       | Start workflows, perform Initial Review                 |
| `MANAGER`        | Manager Approval, team/user & status visibility         |
| `SENIOR_MANAGER` | Senior Manager Review, Final Approval, escalation views |
| `COMPLIANCE`     | Compliance Manual Review, audit access                  |
| `AUDITOR`        | Read-only audit trail and status/user queries           |
| `ADMIN`          | Full access, Final Approval, terminate workflows        |

### 5. Audit trail

Every meaningful event is persisted to the `workflow_audit` table via `AuditService` (written **asynchronously** with
`@Async` so auditing never slows the request path; when the async pool is saturated the caller writes the record
itself, so no event is dropped):

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

- **REST API layer** — Spring MVC controllers for Auth, Workflow, Tasks, Audit, Health.
- **Service layer** — orchestration, JPA persistence, security context access.
- **Engine layer** — embedded Camunda 7 process engine (job executor, history, metrics).
- **Worker layer** — independent external-task clients polling the engine REST API.
- **Persistence layer** — H2 in-memory database; schema managed by Hibernate (`ddl-auto: update`).

### Project structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/          # Spring, Camunda, async, OpenAPI configuration
├── controller/      # REST endpoints (auth, workflow, tasks, audit, health)
├── service/         # Business orchestration
├── worker/          # External task workers + client starter
├── listener/        # Camunda execution & task listeners (audit)
├── security/        # JWT provider, filter, entry point, access-denied handler, identity sync
├── model/           # JPA entities & enums
├── dto/             # Request/response payloads
├── repository/      # Spring Data JPA repositories
└── exception/       # Domain exceptions + global handler
src/main/resources/
├── application.yml
└── bpmn/            # Process definition (.bpmn) and Camunda Forms (.form)
```

---

## 📡 API Reference

Base path: `/api/v1`. All endpoints except auth, health and the API docs require a `Bearer` token.

| Method & Path                               | Role(s)                        | Description                            |
| :------------------------------------------ | :----------------------------- | :------------------------------------- |
| `POST /auth/token`                          | public (username + password)   | Authenticate → access + refresh tokens |
| `POST /auth/refresh`                        | public (`X-Refresh-Token`)     | Rotate tokens                          |
| `GET  /health`                              | public                         | Health probe                           |
| `POST /workflow/start`                      | REVIEWER, MANAGER, ADMIN       | Start an approval workflow             |
| `GET  /workflow/status/{processInstanceId}` | authenticated                  | Current workflow state                 |
| `GET  /workflow/{requestId}`                | authenticated                  | Workflow by request UUID               |
| `GET  /workflow/my-requests`                | authenticated                  | Caller's submissions                   |
| `GET  /workflow/user/{userId}`              | MANAGER, ADMIN, AUDITOR        | Submissions by a user                  |
| `GET  /workflow/by-status/{status}`         | MANAGER, ADMIN, AUDITOR        | Filter by status                       |
| `GET  /workflow/escalated`                  | SENIOR_MANAGER, ADMIN, AUDITOR | Escalated workflows                    |
| `DELETE /workflow/{processInstanceId}`      | ADMIN                          | Terminate a workflow                   |
| `GET  /tasks`                               | authenticated                  | Tasks assigned to / available to me    |
| `GET  /tasks/process/{processInstanceId}`   | authenticated                  | Active tasks for an instance           |
| `GET  /tasks/{taskId}`                      | authenticated                  | Task details                           |
| `POST /tasks/{taskId}/claim` · `/unclaim`   | authenticated                  | Claim / release a task                 |
| `POST /tasks/{taskId}/complete`             | authenticated                  | Complete with a decision               |
| `GET  /audit/**`                            | AUDITOR, ADMIN, COMPLIANCE     | Audit trail queries                    |

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

### Testing the API

A ready-to-run **Postman collection** is included: [
`RegulatoryApprovalSystem.postman_collection.json`](RegulatoryApprovalSystem.postman_collection.json).
It walks a request through the full approval (Initial Review → Manager Approval → automated compliance → Final
Approval), plus a rejection flow, a termination, audit queries and health/security checks. Every request asserts its
status code and key fields, and test scripts capture tokens and ids into collection variables, so it runs top-to-bottom
without copy/paste. Steps that wait for a background worker retry themselves for up to ~30 seconds. Run it in the
Collection Runner, or from the command line:

```bash
postman collection run RegulatoryApprovalSystem.postman_collection.json \
  --env-var baseUrl=http://localhost:8080 --env-var authPassword="$AUTH_DEFAULT_PASSWORD"
```

The `authPassword` collection variable must be set to the API users' password (`AUTH_DEFAULT_PASSWORD`).

**Smoke test.** [`scripts/smoke_test.py`](scripts/smoke_test.py) (Python 3, standard library only) runs ~60 checks
against a running instance: every route through the process (approval, rejection, request-more-information loop,
escalation to a senior manager, manual compliance review, claim/unclaim, termination), role enforcement, read scoping,
the audit trail, and the exposed surface (Swagger, actuator, engine REST API authentication, H2 console):

```bash
python3 scripts/smoke_test.py --base-url http://localhost:8080 \
  --camunda-password "$CAMUNDA_ADMIN_PASSWORD" --api-password "$AUTH_DEFAULT_PASSWORD"
```

Both create workflows, so run them against a local or test instance, not one holding real data.

Interactive docs are also available via Swagger UI at `/swagger-ui.html` when API docs are enabled (`dev` profile or
`API_DOCS_ENABLED=true`).

### Automated tests

`mvn verify` runs the test suite:

- **`AuthUserServiceTest`** — unit tests for credential verification (BCrypt match, unknown user, case-insensitive
  lookup).
- **`WorkerFailureHandlingTest`**, **`ReviewStepRoutingTest`** — worker retries and last-attempt fallbacks, and which
  decisions each task accepts and the stage each leads to.
- **`JwtTokenProviderTest`**, **`JwtAuthenticationFilterTest`**, **`LoginAttemptLimiterTest`**,
  **`ClientAddressResolverTest`**, **`LocalOnlyFilterTest`** — key validation and token types, clearing the Camunda
  identity after each request, login rate limiting and client addresses, and the engine REST API's local-only access.
- **`SecurityAuthorizationIntegrationTest`** — full-stack tests over real HTTP covering the auth matrix (400/401/200,
  refresh preserving roles, server-controlled roles, token types, API and Cockpit login rate limiting), client errors (4xx, not 500), decision validation, request stages, the audit
trail, SLA escalation, the exposed surface (engine REST API, Tasklist, API
docs), **task-level authorization** (a role can only complete tasks its
  candidate group owns), and **read scoping** (a user cannot read another user's workflow/tasks unless they hold an
  oversight role).

Every push and pull request runs these via GitHub Actions (`.github/workflows/ci.yml`).

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

Individual API users can get their own password instead (`AUTH_ADMIN_PASSWORD`, `AUTH_REVIEWER_PASSWORD`,
`AUTH_MANAGER_PASSWORD`, `AUTH_SENIOR_MANAGER_PASSWORD`, `AUTH_COMPLIANCE_PASSWORD`, `AUTH_AUDITOR_PASSWORD`).

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
- **Health**: `http://localhost:8080/actuator/health` (Spring Boot Actuator; the only publicly reachable actuator
  endpoint)

---

## 🐳 Deployment

A multi-stage `Dockerfile` (build + slim JRE runtime, non-root, JVM tuned for a 512 MB footprint) is provided.

```bash
# Docker Compose (requires JWT_SECRET, CAMUNDA_ADMIN_PASSWORD and AUTH_DEFAULT_PASSWORD in the environment)
docker compose up -d
```

The project is also pre-configured for **Render** via `render.yaml` — deploy it with the Blueprint feature and set the
`JWT_SECRET`, `CAMUNDA_ADMIN_PASSWORD` and `AUTH_DEFAULT_PASSWORD` secrets in the dashboard. For an existing service,
add any of these that are missing under **Environment** before deploying: Render only prompts for them when a
Blueprint is first created, and the application will not start without them.

Render is fronted by Cloudflare, so every request reaches the app from a shared proxy. `render.yaml` therefore sets
`CLIENT_IP_HEADER=CF-Connecting-IP`, the header in which Cloudflare passes the real client address, so failed logins are
counted per client rather than per proxy. (If the service was not created from the Blueprint, add it under
**Environment**.) Leave it unset anywhere clients connect directly, as they could then forge the header.
