# Regulatory Approval System - Complete Project Overview

## What Was Done

This document provides a comprehensive explanation of what was implemented, the changes made, and all the concepts behind the Regulatory Approval System.

---

## 1. Changes Made

### 1.1 BPMN Workflow Fix: Delegates to External Tasks

**Problem Identified:** The BPMN workflow file originally referenced Java delegate expressions (`${escalationDelegate}` and `${workflowCompletionDelegate}`) that did not exist in the codebase. This would have caused runtime failures.

**Solution Applied:** Converted all delegate-based service tasks to External Task Workers by changing:

```xml
<!-- Before: Delegate Expression (would fail at runtime) -->
<bpmn:serviceTask id="Task_EscalateInitialReview"
                  camunda:delegateExpression="${escalationDelegate}">

<!-- After: External Task Worker (properly implemented) -->
<bpmn:serviceTask id="Task_EscalateInitialReview"
                  camunda:type="external"
                  camunda:topic="escalation-handler">
```

**Tasks Converted:**

| Task                        | Old Implementation              | New Implementation                   |
| --------------------------- | ------------------------------- | ------------------------------------ |
| Escalate to Manager         | `${escalationDelegate}`         | External task: `escalation-handler`  |
| Escalate to Senior Manager  | `${escalationDelegate}`         | External task: `escalation-handler`  |
| Escalate to Compliance Lead | `${escalationDelegate}`         | External task: `escalation-handler`  |
| Escalate to Admin           | `${escalationDelegate}`         | External task: `escalation-handler`  |
| Complete Approval           | `${workflowCompletionDelegate}` | External task: `workflow-completion` |
| Complete Rejection          | `${workflowCompletionDelegate}` | External task: `workflow-completion` |

### 1.2 Markdown Updates

- Removed "Java Delegates" reference from README.md architecture diagram
- All documentation now consistently describes the External Task Worker pattern

---

## 2. Project Architecture Concepts

### 2.1 What is Camunda BPM?

Camunda is a Business Process Management (BPM) platform that executes BPMN 2.0 workflows. Key concepts:

- **Process Definition**: The BPMN XML file that defines the workflow
- **Process Instance**: A running execution of a process definition
- **User Task**: A task that requires human interaction
- **Service Task**: An automated task executed by the system
- **External Task**: A service task executed by an external worker (decoupled)

### 2.2 External Task Worker Pattern

The External Task pattern decouples business logic from the workflow engine:

```txt
┌──────────────────┐                    ┌──────────────────┐
│  Camunda Engine  │                    │  External Worker │
│                  │  1. Poll for work  │                  │
│  External Task   │ ◄───────────────── │  (Java/Python/   │
│  Queue           │                    │   Node.js/etc)   │
│                  │  2. Fetch & Lock   │                  │
│                  │ ─────────────────► │                  │
│                  │                    │  3. Execute      │
│                  │  4. Complete       │     Business     │
│                  │ ◄───────────────── │     Logic        │
└──────────────────┘                    └──────────────────┘
```

**Why External Tasks instead of Java Delegates?**

| Aspect         | Java Delegates            | External Tasks                 |
| -------------- | ------------------------- | ------------------------------ |
| **Coupling**   | Tightly coupled to engine | Fully decoupled                |
| **Scaling**    | Scales with engine only   | Independent horizontal scaling |
| **Failure**    | Can block workflow        | Built-in retry with backoff    |
| **Deployment** | Must redeploy engine      | Deploy workers independently   |
| **Language**   | Java only                 | Any language                   |

### 2.3 Timer Boundary Events & SLA Enforcement

Timer boundary events in BPMN trigger actions when a task exceeds its SLA:

```xml
<bpmn:boundaryEvent id="Timer_InitialReviewSLA"
                    attachedToRef="Task_InitialReview"
                    cancelActivity="false">  <!-- Non-canceling: task continues -->
    <bpmn:timerEventDefinition>
        <bpmn:timeDuration>PT8H</bpmn:timeDuration>  <!-- 8 hours -->
    </bpmn:timerEventDefinition>
</bpmn:boundaryEvent>
```

**Key Design Decision:** `cancelActivity="false"` means:

- The original task remains active
- Escalation runs as a parallel flow
- Original assignee can still complete the task
- Both the escalation notification and task completion can succeed

### 2.4 JWT Authentication with Camunda

The system uses JWT tokens for authentication, which are synchronized with Camunda's identity service at runtime:

```txt
1. User authenticates → JWT token issued
2. Request arrives with Authorization header
3. JwtAuthenticationFilter validates token
4. Roles extracted and set in SecurityContext
5. CamundaIdentityService syncs roles to Camunda groups
6. Task queries filter by Camunda group membership
```

---

## 3. Complete Workflow Flow

### 3.1 Workflow Stages

```txt
┌─────────────┐
│   START     │
│  Request    │
│  Submitted  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Risk      │  External Task Worker: RiskScoringWorker
│  Scoring    │  Topic: "risk-scoring"
│             │  Calculates risk score (1-100)
└──────┬──────┘
       │
       ▼
┌─────────────┐  Timer: PT8H → Escalation to MANAGER
│  Initial    │  User Task: REVIEWER group
│  Review     │  Decisions: APPROVED, REJECTED, NEEDS_INFO
└──────┬──────┘
       │
       ├── APPROVED ──►┌─────────────┐  Timer: PT24H → Escalation to SENIOR_MANAGER
       │               │  Manager    │  User Task: MANAGER group
       │               │  Approval   │  Decisions: APPROVED, REJECTED, ESCALATE
       │               └──────┬──────┘
       │                      │
       │                      ├── ESCALATE ──►┌─────────────┐
       │                      │               │Senior Mgr   │  User Task: SENIOR_MANAGER
       │                      │               │  Review     │  Decisions: APPROVED, REJECTED
       │                      │               └──────┬──────┘
       │                      │                      │
       │                      ▼ APPROVED ◄──────────┘
       │               ┌─────────────┐  Timer: PT48H → Escalation to COMPLIANCE
       │               │ Compliance  │  External Task: ComplianceCheckWorker
       │               │   Check     │  Topic: "compliance-check"
       │               └──────┬──────┘  Results: PASS, FAIL, REQUIRES_ADDITIONAL_INFO
       │                      │
       │                      ├── REQUIRES_ADDITIONAL_INFO ──►┌─────────────┐
       │                      │                               │ Manual      │  User Task: COMPLIANCE
       │                      │                               │ Compliance  │  Decisions: PASS, FAIL
       │                      │                               │ Review      │
       │                      │                               └──────┬──────┘
       │                      │                                      │
       │                      ▼ PASS ◄──────────────────────────────┘
       │               ┌─────────────┐  Timer: PT8H → Escalation to ADMIN
       │               │   Final     │  User Task: ADMIN, SENIOR_MANAGER
       │               │  Approval   │  Decisions: APPROVED, REJECTED
       │               └──────┬──────┘
       │                      │
       │                      ├── APPROVED ──►┌─────────────┐
       │                      │               │  Complete   │  External Task: WorkflowCompletionWorker
       │                      │               │  Approval   │  Topic: "workflow-completion"
       │                      │               └──────┬──────┘
       │                      │                      │
       │                      │               ┌──────┴──────┐
       │                      │               │   Send      │  External Task: NotificationWorker
       │                      │               │Notification │  Topic: "notification-service"
       │                      │               └──────┬──────┘
       │                      │                      │
       │                      │                      ▼
       │                      │               ┌─────────────┐
       │                      │               │    END      │
       │                      │               │  Approved   │
       │                      │               └─────────────┘
       │                      │
       ▼ REJECTED             ▼ REJECTED (any stage)
┌─────────────┐         ┌─────────────┐
│  Complete   │         │   Send      │
│  Rejection  │────────►│Notification │───►  END: Rejected
└─────────────┘         └─────────────┘
```

### 3.2 External Task Workers

| Worker                       | Topic                  | Responsibility                                                              |
| ---------------------------- | ---------------------- | --------------------------------------------------------------------------- |
| **RiskScoringWorker**        | `risk-scoring`         | Calculates risk score based on request type, department, and other factors  |
| **ComplianceCheckWorker**    | `compliance-check`     | Validates regulatory compliance, returns PASS/FAIL/REQUIRES_ADDITIONAL_INFO |
| **EscalationWorker**         | `escalation-handler`   | Handles SLA breaches, marks request as escalated, records audit             |
| **WorkflowCompletionWorker** | `workflow-completion`  | Finalizes workflow, sets final status, builds rejection reason              |
| **NotificationWorker**       | `notification-service` | Sends email/Slack notifications for approvals, rejections, escalations      |

---

## 4. Security Model

### 4.1 Roles and Permissions

| Role             | Description            | Task Access                           |
| ---------------- | ---------------------- | ------------------------------------- |
| `REVIEWER`       | Initial assessment     | Initial Review tasks, start workflows |
| `MANAGER`        | Business approval      | Manager Approval tasks                |
| `SENIOR_MANAGER` | Escalation handling    | Senior Manager Review, Final Approval |
| `COMPLIANCE`     | Regulatory validation  | Compliance Manual Review tasks        |
| `AUDITOR`        | Read-only audit access | View audit trails only                |
| `ADMIN`          | Full system access     | All operations, terminate workflows   |

### 4.2 Endpoint Security

```java
// Public endpoints
/api/v1/auth/**           - No authentication required
/api/v1/health            - No authentication required
/swagger-ui/**            - No authentication required

// Authenticated endpoints
/api/v1/workflow/**       - Any authenticated user
/api/v1/tasks/**          - Any authenticated user (filtered by role)

// Restricted endpoints
/api/v1/audit/**          - AUDITOR, ADMIN, COMPLIANCE only
```

---

## 5. Error Handling & Fallbacks

### 5.1 Worker Retry Strategy

All external task workers implement retry with exponential backoff:

```java
private void handleFailure(ExternalTask task, ExternalTaskService service, Exception e) {
    int retries = task.getRetries() != null ? task.getRetries() : MAX_RETRIES;

    if (retries > 0) {
        // Exponential backoff: 5s → 10s → 15s
        long backoff = RETRY_TIMEOUT * (MAX_RETRIES - retries + 1);
        service.handleFailure(task, e.getMessage(), e.getClass().getName(), retries - 1, backoff);
    } else {
        // Worker-specific fallback (see table below)
    }
}
```

### 5.2 Fallback Strategies by Worker

| Worker                   | Max Retries Failed Action        | Rationale                      |
| ------------------------ | -------------------------------- | ------------------------------ |
| RiskScoringWorker        | Complete with default score (50) | Don't block workflow           |
| ComplianceCheckWorker    | Create BPMN incident             | Critical for compliance        |
| EscalationWorker         | Complete with error flag         | Escalation is informational    |
| WorkflowCompletionWorker | Create BPMN incident             | Critical for data integrity    |
| NotificationWorker       | Complete with failure flag       | Notifications are non-blocking |

---

## 6. Audit Trail

### 6.1 Audit Events Captured

| Event Type         | Trigger                     | Data Captured               |
| ------------------ | --------------------------- | --------------------------- |
| WORKFLOW_STARTED   | Process instance created    | Request details, submitter  |
| TASK_CREATED       | User task activated         | Task name, candidate groups |
| TASK_CLAIMED       | User claims task            | Assignee, timestamp         |
| TASK_COMPLETED     | User completes task         | Decision, comment           |
| DECISION_MADE      | Approval/rejection recorded | Decision value, performer   |
| SLA_BREACH         | Timer fires                 | Original task, SLA duration |
| TASK_ESCALATED     | Escalation processed        | Escalation target           |
| WORKFLOW_COMPLETED | Process ends                | Final status, outcome       |

### 6.2 Audit Service Pattern

```java
auditService.recordAuditEvent(
    processInstanceId,    // Links to workflow
    taskId,               // Links to specific task
    taskName,             // Human-readable task name
    eventType,            // AuditEventType enum
    oldValue,             // Previous state (if applicable)
    newValue,             // New state/decision
    performedBy,          // Username or "system"
    role,                 // User's role
    comment               // Additional context
);
```

---

## 7. Database Schema

### 7.1 Application Tables

**regulatory_request** - Main business entity

- `id`, `process_instance_id`, `request_title`, `request_type`
- `status`, `current_stage`, `current_assignee`
- `reviewer_decision`, `manager_decision`, `final_decision`
- `risk_score`, `compliance_result`
- `escalated`, `escalation_reason`, `escalated_at`
- `created_at`, `updated_at`, `completed_at`

**workflow_audit** - Audit trail

- `id`, `process_instance_id`, `task_id`, `task_name`
- `event_type`, `old_value`, `new_value`
- `performed_by`, `role`, `comment`, `timestamp`

**sla_configuration** - SLA settings (pre-populated)

- `stage_name`, `sla_duration_hours`, `escalation_target_role`

### 7.2 Camunda Tables (Auto-managed)

- `ACT_RU_*` - Runtime tables (active instances, tasks)
- `ACT_HI_*` - History tables (completed workflows)
- `ACT_RE_*` - Repository tables (process definitions)

---

## 8. Project Structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/                          # Spring configuration
│   ├── SecurityConfig.java          # JWT + Spring Security
│   ├── CamundaConfig.java           # Camunda engine setup
│   ├── OpenApiConfig.java           # Swagger documentation
│   └── AsyncConfig.java             # Async processing
│
├── controller/                      # REST API endpoints
│   ├── AuthController.java          # JWT token generation
│   ├── WorkflowController.java      # Workflow lifecycle
│   ├── TaskController.java          # Task operations
│   ├── AuditController.java         # Audit trail queries
│   └── HealthController.java        # Health checks
│
├── service/                         # Business logic
│   ├── WorkflowService.java         # Start/query workflows
│   ├── WorkflowTaskService.java     # Task claim/complete
│   └── AuditService.java            # Audit recording
│
├── worker/                          # External Task Workers
│   ├── ExternalTaskWorkerConfig.java  # Client configuration
│   ├── RiskScoringWorker.java       # Risk assessment
│   ├── ComplianceCheckWorker.java   # Compliance validation
│   ├── EscalationWorker.java        # SLA breach handling
│   ├── WorkflowCompletionWorker.java # Workflow finalization
│   └── NotificationWorker.java      # Notification dispatch
│
├── listener/                        # Camunda event listeners
│   ├── TaskAuditListener.java       # Task lifecycle auditing
│   ├── WorkflowStartListener.java   # Process start auditing
│   └── WorkflowEndListener.java     # Process end auditing
│
├── security/                        # JWT security components
│   ├── JwtTokenProvider.java        # Token generation/validation
│   ├── JwtAuthenticationFilter.java # Request filter
│   ├── SecurityUtils.java           # Current user utilities
│   ├── UserPrincipal.java           # Security principal
│   └── CamundaIdentityService.java  # JWT-Camunda sync
│
├── model/                           # Domain model
│   ├── entity/                      # JPA entities
│   │   ├── RegulatoryRequest.java
│   │   └── WorkflowAudit.java
│   └── enums/                       # Enumerations
│       ├── ApprovalStatus.java
│       ├── AuditEventType.java
│       ├── ComplianceResult.java
│       └── UserRole.java
│
├── dto/                             # Data transfer objects
│   ├── request/                     # API request payloads
│   └── response/                    # API response payloads
│
├── repository/                      # Data access layer
│   ├── RegulatoryRequestRepository.java
│   └── WorkflowAuditRepository.java
│
└── exception/                       # Exception handling
    ├── GlobalExceptionHandler.java  # Centralized error handling
    ├── ResourceNotFoundException.java
    ├── WorkflowException.java
    └── TaskOperationException.java
```

---

## 9. How to Run

### 9.1 Prerequisites

- Java 21+
- Maven 3.9+
- Docker (optional)

### 9.2 Quick Start

```bash
# Build the application
mvn clean package -DskipTests

# Run the application (uses H2 in-memory database - no setup required)
mvn spring-boot:run

# Or with Docker Compose
docker-compose up -d
```

### 9.3 Access Points

- **Swagger UI**: <http://localhost:8080/swagger-ui.html>
- **Camunda Webapp**: <http://localhost:8080/camunda>
- **H2 Console**: <http://localhost:8080/h2-console>
- **Health Check**: <http://localhost:8080/api/v1/health>

### 9.4 Test the Workflow

```bash
# 1. Get a JWT token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "reviewer1", "roles": ["REVIEWER"]}'

# 2. Start a workflow
curl -X POST http://localhost:8080/api/v1/workflow/start \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requestTitle": "New Product Approval",
    "requestType": "FINANCIAL_PRODUCT",
    "department": "INVESTMENT",
    "priority": "HIGH"
  }'

# 3. Get your tasks
curl -X GET http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer <token>"

# 4. Complete a task
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "APPROVED", "comment": "Looks good"}'
```

---

## 10. Summary

The Regulatory Approval System is a production-ready enterprise workflow application that demonstrates:

1. **BPMN 2.0 Workflow**: Multi-stage approval with SLA enforcement
2. **External Task Workers**: Decoupled, scalable business logic execution
3. **JWT Security**: Stateless authentication integrated with Camunda
4. **Comprehensive Audit Trail**: Every workflow event tracked
5. **Error Resilience**: Retry strategies and fallback handling
6. **Clean Architecture**: Layered design with clear separation of concerns

All service tasks in the BPMN workflow use External Task Workers, ensuring maximum decoupling, independent scaling, and failure resilience.
