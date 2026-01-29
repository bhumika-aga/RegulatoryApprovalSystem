# Regulatory Approval System - Project Overview

> 🚀 **Live Demo**: <https://regulatory-approval-system.onrender.com/swagger-ui.html>

This document provides a comprehensive explanation of the Regulatory Approval System, covering the concepts, architecture, and implementation logic behind it.

---

## What is This Project?

The **Regulatory Approval System** is an enterprise-grade workflow application that automates multi-stage approval processes. It's designed for industries requiring strict regulatory compliance, such as:

- **Banking & Financial Services** - Product approvals, loan decisions
- **Insurance** - Policy approvals, claims processing
- **Healthcare** - Treatment approvals, compliance reviews

---

## Core Concepts

### 1. BPMN Workflow Engine (Camunda)

**BPMN** (Business Process Model and Notation) is a standard for modeling business processes visually. This project uses **Camunda BPM** to:

- Define the approval workflow as a visual flowchart
- Manage task assignment and state transitions
- Enforce SLA timers and escalation rules
- Track audit history automatically

```txt
Submit → Review → Manager → Compliance → Final → Complete
          (8h)    (24h)       (48h)       (8h)
```

### 2. External Task Worker Pattern

Instead of embedding business logic inside Camunda (Java Delegates), we use **External Task Workers**:

```txt
Camunda Engine                    External Workers
     │                                  │
     │  1. Create task on topic         │
     │ ─────────────────────────────► │
     │                                  │
     │  2. Worker polls for tasks       │
     │ ◄───────────────────────────── │
     │                                  │
     │  3. Worker completes task        │
     │ ◄───────────────────────────── │
```

**Benefits:**

- **Decoupled** - Workers can be deployed independently
- **Scalable** - Workers scale separately from the engine
- **Resilient** - Automatic retries with fallback strategies
- **Polyglot** - Workers can be written in any language

### 3. JWT Authentication

The system uses **JSON Web Tokens (JWT)** for stateless authentication:

1. Client requests a token with username, roles, and department
2. Server signs the token with a secret key
3. Client includes token in all subsequent requests
4. Server validates token and extracts user identity

### 4. Role-Based Access Control (RBAC)

Six roles with specific permissions:

| Role           | Purpose             | Can Do                            |
| -------------- | ------------------- | --------------------------------- |
| REVIEWER       | Initial assessment  | Start workflows, complete reviews |
| MANAGER        | Business approval   | Approve/reject, escalate          |
| SENIOR_MANAGER | Escalation handling | Handle exceeded SLAs              |
| COMPLIANCE     | Regulatory checks   | Verify compliance                 |
| AUDITOR        | Read-only access    | View audit trails                 |
| ADMIN          | Full access         | All operations                    |

---

## Architecture Layers

```txt
┌─────────────────────────────────────┐
│       REST API Controllers          │  ← HTTP endpoints
├─────────────────────────────────────┤
│          Service Layer              │  ← Business orchestration
├─────────────────────────────────────┤
│       External Task Workers         │  ← Decoupled business logic
├─────────────────────────────────────┤
│        Camunda BPM Engine           │  ← Workflow execution
├─────────────────────────────────────┤
│         H2 Database                 │  ← Data persistence
└─────────────────────────────────────┘
```

---

## Key Components

### External Task Workers (5 total)

| Worker                   | Topic                  | Purpose                         |
| ------------------------ | ---------------------- | ------------------------------- |
| RiskScoringWorker        | `risk-scoring`         | Calculates risk score (0-100)   |
| ComplianceCheckWorker    | `compliance-check`     | Validates regulatory compliance |
| EscalationWorker         | `escalation-handler`   | Handles SLA breach escalations  |
| WorkflowCompletionWorker | `workflow-completion`  | Finalizes approval/rejection    |
| NotificationWorker       | `notification-service` | Sends notifications             |

### Camunda Listeners (3 total)

| Listener              | Purpose                            |
| --------------------- | ---------------------------------- |
| WorkflowStartListener | Records workflow start in audit    |
| TaskAuditListener     | Records task create/claim/complete |
| WorkflowEndListener   | Records workflow completion        |

---

## Workflow Process

### Happy Path (Full Approval)

1. **Submit Request** → Creates workflow, calculates risk score
2. **Initial Review** (REVIEWER, 8h SLA) → Approve/Reject/Need Info
3. **Manager Approval** (MANAGER, 24h SLA) → Approve/Reject/Escalate
4. **Compliance Check** (External Worker, 48h SLA) → Auto-check + manual if needed
5. **Final Approval** (ADMIN/SENIOR_MANAGER) → Final decision
6. **Complete** → Notification sent

### Escalation Flow

When a task exceeds its SLA:

1. Timer fires (non-canceling - original task stays active)
2. EscalationWorker captures the breach
3. Records audit event
4. Notifies escalation target (Manager → Senior Manager → Admin)

---

## Error Handling

Each worker has a specific fallback strategy:

| Worker                | On Failure                    |
| --------------------- | ----------------------------- |
| RiskScoringWorker     | Returns default score (50)    |
| ComplianceCheckWorker | Creates BPMN error → incident |
| EscalationWorker      | Completes with error flag     |
| NotificationWorker    | Completes with failure flag   |

Retry strategy uses exponential backoff: 5s → 10s → 15s

---

## Security Implementation

### No Hardcoded Secrets

All sensitive values are environment variables:

- `JWT_SECRET` - Signing key for JWT tokens
- `CAMUNDA_ADMIN_PASSWORD` - Camunda admin password

### Token Validation

```java
1. Extract Bearer token from Authorization header
2. Validate signature using JWT_SECRET
3. Check expiration
4. Extract claims (username, roles, department)
5. Set SecurityContext for request
6. Sync roles to Camunda groups for task queries
```

---

## Data Model

### RegulatoryRequest Entity

| Field             | Purpose                                        |
| ----------------- | ---------------------------------------------- |
| id                | Unique identifier (UUID)                       |
| processInstanceId | Links to Camunda workflow                      |
| requestTitle      | User-provided title                            |
| requestType       | FINANCIAL_PRODUCT, INSURANCE_PRODUCT, etc.     |
| status            | PENDING_REVIEW → IN_REVIEW → APPROVED/REJECTED |
| riskScore         | Calculated risk (0-100)                        |
| escalated         | True if SLA was breached                       |

### WorkflowAudit Entity

| Field             | Purpose                                        |
| ----------------- | ---------------------------------------------- |
| processInstanceId | Links to workflow                              |
| taskName          | Which task was affected                        |
| eventType         | TASK_CREATED, TASK_COMPLETED, SLA_BREACH, etc. |
| performedBy       | Username who performed action                  |
| timestamp         | When it happened                               |

---

## Deployment

### Local Development

```bash
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=admin123
mvn spring-boot:run
```

### Render.com (Free Tier)

1. Push code to GitHub
2. Create Web Service on Render
3. Set runtime to Docker
4. Set environment variables
5. Deploy

---

## Technology Stack

| Component | Technology            | Version |
| --------- | --------------------- | ------- |
| Runtime   | Java                  | 17      |
| Framework | Spring Boot           | 3.5.9   |
| Workflow  | Camunda BPM           | 7.22.0  |
| Database  | H2 (in-memory)        | 2.3.x   |
| Security  | Spring Security + JWT | 6.x     |
| API Docs  | SpringDoc OpenAPI     | 2.8.0   |

---

## File Structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/          # Configuration classes
├── controller/      # REST API endpoints
├── service/         # Business orchestration
├── worker/          # External task workers
├── listener/        # Camunda event listeners
├── security/        # JWT authentication
├── model/           # Entities & enums
├── dto/             # Request/Response objects
├── repository/      # Data access
└── exception/       # Error handling

src/main/resources/
├── bpmn/            # BPMN workflow + forms
└── application.yml  # Configuration
```

---

## Key Design Decisions

1. **External Workers Only** - Maximum decoupling, no Java Delegates
2. **Non-Canceling Timers** - Escalations don't cancel original tasks
3. **JWT for Camunda Identity** - Single source of authentication
4. **H2 for Development** - Zero-setup database, easy PostgreSQL migration
5. **Render-Ready** - Docker + Blueprint configuration included

---

## Quick Reference

### API Endpoints

| Endpoint                      | Method | Purpose         |
| ----------------------------- | ------ | --------------- |
| `/api/v1/auth/token`          | POST   | Get JWT token   |
| `/api/v1/workflow/start`      | POST   | Start workflow  |
| `/api/v1/tasks`               | GET    | Get your tasks  |
| `/api/v1/tasks/{id}/complete` | POST   | Complete a task |
| `/api/v1/health`              | GET    | Health check    |

### Access Points

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Camunda Webapp: <http://localhost:8080/camunda>
- H2 Console: <http://localhost:8080/h2-console>
- Health Check: <http://localhost:8080/api/v1/health>
