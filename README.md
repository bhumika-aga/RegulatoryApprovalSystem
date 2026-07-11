# Regulatory Approval System

[![CI](https://github.com/bhumika-aga/RegulatoryApprovalSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/bhumika-aga/RegulatoryApprovalSystem/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.15-green.svg)](https://spring.io/projects/spring-boot)
[![Camunda](https://img.shields.io/badge/Camunda-7.24.0-blue.svg)](https://camunda.com/)
[![H2](https://img.shields.io/badge/H2-In--Memory-blue.svg)](https://www.h2database.com/)

An enterprise-grade, BPMN-driven regulatory approval workflow built with **Spring Boot 3** and **Camunda 7**. It models
the kind of multi-stage approval process common in BFSI (Banking, Financial Services, Insurance) and healthcare:
a request is submitted, automatically risk-scored, reviewed by humans across several tiers, validated for compliance,
finally approved, and audited end-to-end — with SLA timers escalating any stage that stalls.

---

## 🚀 Overview

The system orchestrates the approval process with the **External Task Worker pattern**: the workflow engine owns the
*flow*, while all business logic runs in independent worker components that poll the engine for work. This keeps the
process model declarative and lets the business logic fail, retry, and scale independently of the engine.

### Core flow

```txt
Submit ─▶ Risk Scoring ─▶ Initial Review ─▶ Manager Approval ─▶ Compliance Check ─▶ Final Approval ─▶ Complete
         (automated)       (REVIEWER)         (MANAGER)          (automated)         (ADMIN /          (automated)
                              8h SLA            24h SLA           48h SLA             SENIOR_MANAGER)
                                │                  │                  │              8h SLA
                                ▼                  ▼                  ▼                 │
                           Escalate to        Escalate to       Manual Review          ▼
                            Manager          Senior Manager    (COMPLIANCE, if      Escalate to
                                                                 ambiguous)            Admin
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
  roles. They are driven through the REST API and rendered with Camunda Forms (`*.form` under `resources/bpmn/`).
- **Service tasks** are implemented as **external tasks** (`camunda:type="external"`). The engine publishes work to a
  *topic*; a worker locks, executes and completes it. No business code runs inside the engine.
- **Exclusive gateways** route on process variables set when a task completes — e.g.
  `${reviewerDecision == 'APPROVED'}`,
  `${managerDecision == 'ESCALATE'}`, `${complianceResult == 'PASS'}`.
- **Non-cancelling boundary timers** enforce SLAs (`PT8H`, `PT24H`, `PT48H`, `PT8H`). When a timer fires it spawns a
  parallel escalation branch (escalate + notify) **without** cancelling the still-pending human task.

### 2. External Task Workers

Five workers, one per topic, each implementing `ExternalTaskHandler` and subscribing on `ApplicationReadyEvent`:

| Worker                     | Topic                  | Responsibility                                                                 |
|:---------------------------|:-----------------------|:-------------------------------------------------------------------------------|
| `RiskScoringWorker`        | `risk-scoring`         | Scores 0–100 from request type + department, categorises (MINIMAL→CRITICAL)    |
| `ComplianceCheckWorker`    | `compliance-check`     | Automated regulatory validation → `PASS` / `FAIL` / `REQUIRES_ADDITIONAL_INFO` |
| `EscalationWorker`         | `escalation-handler`   | Records SLA breach, flags the request as escalated, writes audit events        |
| `WorkflowCompletionWorker` | `workflow-completion`  | Finalises request status (`APPROVED` / `REJECTED`) and completion audit        |
| `NotificationWorker`       | `notification-service` | Sends approval / rejection / escalation notifications (logged by default)      |

The shared `ExternalTaskClient` is created with auto-fetching **disabled** and is started by
`ExternalTaskClientStarter` only after every worker has opened its subscription, so the very first poll already covers
all topics.

### 3. Resilience & fallbacks

Every worker wraps its logic in try/catch and degrades deliberately on failure. Retries decrement on each attempt with a
fixed back-off; the polling client itself uses an exponential back-off (`500ms × 2, capped at 10s`) when no work is
available. On exhausting retries each worker applies a fallback suited to how critical it is:

- **Risk scoring** — non-blocking: falls back to a default score of `50` / `MEDIUM` and completes, so review is never
  blocked by the scoring engine.
- **Compliance / Completion** — critical: raise a **BPMN error** (`COMPLIANCE_ERROR` / `COMPLETION_ERROR`) which creates
  an incident for manual intervention rather than silently passing.
- **Escalation / Notification** — best-effort: complete with an error flag so a failed notification never blocks the
  business process.

### 4. Security model

Authentication is **stateless JWT** (HS512). A token carries the subject (username), a `roles` claim and a `department`
claim — issued by `POST /api/v1/auth/token` after verifying a **username + password** against a configured user store
(`app.security.auth.users`; passwords are BCrypt-hashed at startup). **Roles and department come from that store, not
from the request**, so a caller cannot grant itself privileges. This store is a self-contained stand-in for an external
IdP in production. `POST /api/v1/auth/refresh` re-derives the current roles/department from the store rather than
trusting
the refresh token.

Seeded demo users (one per role — all share `AUTH_DEFAULT_PASSWORD`, overridable individually via
`AUTH_<ROLE>_PASSWORD`): `admin`, `reviewer`, `manager`, `senior.manager`, `compliance`, `auditor`.

- `JwtAuthenticationFilter` validates the token, builds the `UserPrincipal` / authorities, and synchronizes the
  authenticated user id into Camunda's `IdentityService` so the engine can resolve candidate-group task assignment.
- URL- and method-level authorization (`@PreAuthorize`) enforce role access.
- `JwtAuthenticationEntryPoint` returns a clean **401** when authentication is missing/invalid; `JwtAccessDeniedHandler`
  returns **403** when an authenticated user lacks the required role.

**Surface hardening.** Only what has to be public is public:

- `/actuator/health` is anonymous (platform health check); all other actuator endpoints require authentication, and
  health details are shown only to authenticated callers.
- The Camunda webapp (`/camunda/**`) is protected by Camunda's own login — only the configured `admin` user exists.
- The raw engine REST API (`/engine-rest/**`), used only by the external task workers, is guarded by Camunda's
  `ProcessEngineAuthenticationFilter` (HTTP Basic); the worker client authenticates with the admin credentials.
- The H2 console is disabled outside the `dev` profile.

| Role             | Capabilities                                            |
|:-----------------|:--------------------------------------------------------|
| `REVIEWER`       | Start workflows, perform Initial Review                 |
| `MANAGER`        | Manager Approval, team/user & status visibility         |
| `SENIOR_MANAGER` | Senior Manager Review, Final Approval, escalation views |
| `COMPLIANCE`     | Compliance Manual Review, audit access                  |
| `AUDITOR`        | Read-only audit trail and status/user queries           |
| `ADMIN`          | Full access, Final Approval, terminate workflows        |

### 5. Audit trail

Every meaningful event is persisted to the `workflow_audit` table via `AuditService` (written **asynchronously** with
`@Async` so auditing never slows the request path):

- **Workflow events** — `WORKFLOW_STARTED` / `WORKFLOW_COMPLETED` / `WORKFLOW_TERMINATED` via execution listeners and
  the completion worker.
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
|:--------------------------------------------|:-------------------------------|:---------------------------------------|
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

`complete` accepts a `decision` of `APPROVED`, `REJECTED`, `NEEDS_INFO`, `ESCALATE`, `PASS` or `FAIL` (validated),
an optional `comment`, and optional `additionalVariables`.

### Testing the API

A ready-to-run **Postman collection** is included: [
`RegulatoryApprovalSystem.postman_collection.json`](RegulatoryApprovalSystem.postman_collection.json).
Import it, then run the requests top-to-bottom (or via the Collection Runner) — test scripts capture the JWT tokens,
process-instance id, request id and task id into collection variables automatically, so the full approval flow runs
without manual copy/paste. Each key request ships with a saved example response captured from a live run.

Interactive docs are also available via Swagger UI at `/swagger-ui.html`.

### Automated tests

`mvn verify` runs the test suite:

- **`AuthUserServiceTest`** — unit tests for credential verification (BCrypt match, unknown user, case-insensitive
  lookup).
- **`SecurityAuthorizationIntegrationTest`** — full-stack tests over real HTTP covering the auth matrix (400/401/200,
  refresh preserving roles, server-controlled roles), **task-level authorization** (a role can only complete tasks its
  candidate group owns), and **read scoping** (a user cannot read another user's workflow/tasks unless they hold an
  oversight role).

Every push and pull request runs these via GitHub Actions (`.github/workflows/ci.yml`).

---

## 🛠️ Technology Stack

- **Framework**: Spring Boot 3.5.15 (Java 21)
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

```bash
export JWT_SECRET=$(openssl rand -base64 64)   # Base64, decodes to ≥ 64 bytes for HS512
export CAMUNDA_ADMIN_PASSWORD=admin
export AUTH_DEFAULT_PASSWORD='ChangeMe123!'    # password for the seeded API users (admin, reviewer, manager, ...)
```

### Run locally

```bash
mvn clean package
JWT_SECRET=$JWT_SECRET CAMUNDA_ADMIN_PASSWORD=$CAMUNDA_ADMIN_PASSWORD java -jar target/regulatory-approval-system-1.0.0.jar
# or, for development (enables the H2 console — see below):
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
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
# Docker Compose (requires JWT_SECRET and CAMUNDA_ADMIN_PASSWORD in the environment)
docker-compose up -d
```

The project is also pre-configured for **Render** via `render.yaml` — deploy it with the Blueprint feature and set the
`JWT_SECRET` and `CAMUNDA_ADMIN_PASSWORD` secrets in the dashboard.
