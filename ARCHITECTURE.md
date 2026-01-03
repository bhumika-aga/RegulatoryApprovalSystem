# Architecture & Design Documentation

This document provides a comprehensive overview of the Regulatory Approval System's architecture, design patterns, and implementation concepts.

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture Layers](#2-architecture-layers)
3. [BPMN Workflow Engine](#3-bpmn-workflow-engine)
4. [External Task Worker Pattern](#4-external-task-worker-pattern)
5. [Security Architecture](#5-security-architecture)
6. [Data Model](#6-data-model)
7. [Error Handling & Resilience](#7-error-handling--resilience)
8. [Key Design Patterns](#8-key-design-patterns)

---

## 1. System Overview

The Regulatory Approval System is an enterprise-grade workflow application that implements a multi-stage approval process for regulatory requests. It's designed for industries like BFSI (Banking, Financial Services, Insurance) and healthcare where regulatory compliance is critical.

### Core Capabilities

```txt
┌─────────────────────────────────────────────────────────────────┐
│                    Regulatory Approval System                    │
├─────────────────────────────────────────────────────────────────┤
│  Multi-Stage Approval    │  Role-Based Access    │  Audit Trail │
│  SLA Enforcement         │  Risk Scoring         │  Escalation  │
│  Compliance Checking     │  JWT Authentication   │  Camunda BPM │
└─────────────────────────────────────────────────────────────────┘
```

### Approval Flow

```txt
Submit → Initial Review → Manager Approval → Compliance Check → Final Approval → Complete
           (8h SLA)         (24h SLA)          (48h SLA)          (8h SLA)
              ↓                 ↓                  ↓                  ↓
          Escalate to      Escalate to        Manual Review      Escalate to
           Manager        Senior Manager      (if required)        Admin
```

---

## 2. Architecture Layers

The system follows a layered architecture pattern:

```txt
┌─────────────────────────────────────────────────────────────────┐
│                      Presentation Layer                          │
│  REST Controllers: Auth, Workflow, Task, Audit, Health          │
├─────────────────────────────────────────────────────────────────┤
│                       Service Layer                              │
│  WorkflowService │ WorkflowTaskService │ AuditService           │
├─────────────────────────────────────────────────────────────────┤
│                    Business Logic Layer                          │
│  External Task Workers: Risk, Compliance, Escalation, etc.      │
├─────────────────────────────────────────────────────────────────┤
│                      Engine Layer                                │
│  Camunda BPM Engine │ Process Runtime │ Task Service            │
├─────────────────────────────────────────────────────────────────┤
│                    Persistence Layer                             │
│  JPA Repositories │ H2 Database │ Camunda Tables                │
└─────────────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer              | Purpose                                                | Components            |
| ------------------ | ------------------------------------------------------ | --------------------- |
| **Presentation**   | HTTP request handling, validation, response formatting | Controllers, DTOs     |
| **Service**        | Business orchestration, transaction management         | Services              |
| **Business Logic** | Decoupled processing, domain rules                     | External Task Workers |
| **Engine**         | Workflow execution, state management                   | Camunda BPM           |
| **Persistence**    | Data storage, queries                                  | JPA, Repositories     |

---

## 3. BPMN Workflow Engine

### Why Camunda BPM?

Camunda BPM provides:

- Visual process modeling with BPMN 2.0
- Built-in task management and assignment
- Timer events for SLA enforcement
- External task pattern for decoupled execution
- Comprehensive history and audit

### Process Variables

The workflow uses these key variables:

| Variable           | Type    | Purpose                            |
| ------------------ | ------- | ---------------------------------- |
| `requestId`        | UUID    | Links to RegulatoryRequest entity  |
| `submitterId`      | String  | User who submitted the request     |
| `riskScore`        | Integer | Calculated risk level (0-100)      |
| `reviewerDecision` | String  | APPROVED/REJECTED/NEEDS_INFO       |
| `managerDecision`  | String  | APPROVED/REJECTED/ESCALATE         |
| `complianceResult` | String  | PASS/FAIL/REQUIRES_ADDITIONAL_INFO |
| `finalDecision`    | String  | APPROVED/REJECTED                  |
| `escalated`        | Boolean | Whether SLA was breached           |

### Timer Boundary Events

Non-canceling timers allow escalation while keeping original task active:

```xml
<boundaryEvent cancelActivity="false">
    <timerEventDefinition>
        <timeDuration>PT8H</timeDuration>
    </timerEventDefinition>
</boundaryEvent>
```

**Why non-canceling?**

- Original assignee can still complete the task
- Escalation runs as a parallel notification flow
- Prevents work loss if task is nearly complete

---

## 4. External Task Worker Pattern

### Pattern Overview

```txt
┌──────────────┐                    ┌──────────────┐
│   Camunda    │  1. Poll for tasks │   External   │
│   Engine     │ ◄────────────────── │   Worker     │
│              │                    │              │
│   External   │  2. Fetch & Lock   │   Execute    │
│   Task Queue │ ──────────────────► │   Logic      │
│              │                    │              │
│              │  3. Complete       │              │
│              │ ◄────────────────── │              │
└──────────────┘                    └──────────────┘
```

### Benefits Over Java Delegates

| Aspect     | Java Delegates            | External Workers         |
| ---------- | ------------------------- | ------------------------ |
| Coupling   | Tightly coupled to engine | Fully decoupled          |
| Scaling    | Scale with engine         | Scale independently      |
| Failure    | Blocks workflow           | Automatic retry/incident |
| Deployment | Redeploy engine           | Deploy independently     |
| Language   | Java only                 | Any language             |

### Worker Implementation Pattern

Each worker follows this structure:

```java
@Component
public class ExampleWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "topic-name";
    private static final int MAX_RETRIES = 3;

    @PostConstruct
    public void subscribe() {
        client.subscribe(TOPIC_NAME)
              .lockDuration(30000)
              .handler(this)
              .open();
    }

    @Override
    public void execute(ExternalTask task, ExternalTaskService service) {
        try {
            // 1. Extract variables
            // 2. Execute business logic
            // 3. Complete with output variables
            service.complete(task, outputVariables);
        } catch (Exception e) {
            handleFailure(task, service, e);
        }
    }

    private void handleFailure(...) {
        // Retry with exponential backoff or fallback
    }
}
```

### Worker Topics

| Topic                  | Worker                   | Failure Strategy              |
| ---------------------- | ------------------------ | ----------------------------- |
| `risk-scoring`         | RiskScoringWorker        | Default score (50) on failure |
| `compliance-check`     | ComplianceCheckWorker    | BPMN error → incident         |
| `escalation-handler`   | EscalationWorker         | Complete with error flag      |
| `workflow-completion`  | WorkflowCompletionWorker | BPMN error → incident         |
| `notification-service` | NotificationWorker       | Complete with failure flag    |

---

## 5. Security Architecture

### Authentication Flow

```txt
1. Client → POST /api/v1/auth/token {username, roles, department}
                    ↓
2. JwtTokenProvider.generateToken()
                    ↓
3. JWT returned to client (valid 24 hours)
                    ↓
4. Client → Request with Authorization: Bearer <token>
                    ↓
5. JwtAuthenticationFilter validates token
                    ↓
6. SecurityContextHolder.setAuthentication()
                    ↓
7. CamundaIdentityService syncs roles to Camunda groups
```

### JWT Token Structure

```json
{
  "sub": "user123",
  "roles": ["REVIEWER", "MANAGER"],
  "department": "RISK",
  "iss": "regulatory-approval-system",
  "iat": 1704067200,
  "exp": 1704153600
}
```

### Role-Based Access Control

| Role           | Permissions                                          |
| -------------- | ---------------------------------------------------- |
| REVIEWER       | Start workflows, complete Initial Review tasks       |
| MANAGER        | Complete Manager Approval tasks, view team workflows |
| SENIOR_MANAGER | Handle escalations, Final Approval                   |
| COMPLIANCE     | Complete Compliance Review tasks                     |
| AUDITOR        | Read-only access to audit trails                     |
| ADMIN          | Full access, terminate workflows                     |

### Camunda Integration

The `CamundaAuthenticationProvider` bridges Spring Security with Camunda:

```java
public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
    Principal principal = request.getUserPrincipal();
    if (principal == null) {
        // Development fallback - return admin
        return new AuthenticationResult("admin", true);
    }
    return new AuthenticationResult(principal.getName(), true);
}
```

This allows:

- Camunda Cockpit/Tasklist to work without separate login
- Task assignment to use JWT roles as Camunda groups
- Seamless SSO experience

---

## 6. Data Model

### Entity Relationship

```txt
┌─────────────────────┐          ┌─────────────────────┐
│  RegulatoryRequest  │          │   WorkflowAudit     │
├─────────────────────┤          ├─────────────────────┤
│ id (UUID, PK)       │          │ id (UUID, PK)       │
│ processInstanceId   │◄────────►│ processInstanceId   │
│ requestTitle        │          │ taskId              │
│ requestType         │          │ taskName            │
│ department          │          │ eventType           │
│ submitterId         │          │ oldValue            │
│ status              │          │ newValue            │
│ riskScore           │          │ performedBy         │
│ currentStage        │          │ role                │
│ escalated           │          │ comment             │
│ createdAt           │          │ timestamp           │
│ completedAt         │          └─────────────────────┘
└─────────────────────┘
```

### Approval Status Lifecycle

```txt
PENDING_REVIEW → IN_REVIEW → PENDING_APPROVAL → APPROVED
                     ↓              ↓
                 REJECTED      REJECTED
                     ↓
            ADDITIONAL_INFO_REQUIRED → (loops back to IN_REVIEW)
```

### Audit Event Types

```java
public enum AuditEventType {
    WORKFLOW_STARTED,
    TASK_CREATED,
    TASK_CLAIMED,
    TASK_COMPLETED,
    TASK_ESCALATED,
    SLA_BREACH,
    DECISION_MADE,
    COMPLIANCE_CHECK_PASSED,
    COMPLIANCE_CHECK_FAILED,
    WORKFLOW_COMPLETED,
    WORKFLOW_TERMINATED
}
```

---

## 7. Error Handling & Resilience

### Retry Strategy

Each worker implements exponential backoff:

```java
private void handleFailure(ExternalTask task, ExternalTaskService service, Exception e) {
    int retries = task.getRetries() != null ? task.getRetries() : MAX_RETRIES;

    if (retries > 0) {
        long backoff = RETRY_TIMEOUT * (MAX_RETRIES - retries + 1);
        service.handleFailure(task, e.getMessage(), e.getClass().getName(), retries - 1, backoff);
    } else {
        // Fallback strategy (varies by worker)
    }
}
```

### Fallback Strategies by Criticality

| Worker                   | Criticality | Fallback                         |
| ------------------------ | ----------- | -------------------------------- |
| RiskScoringWorker        | Medium      | Complete with default score (50) |
| ComplianceCheckWorker    | Critical    | BPMN error → creates incident    |
| EscalationWorker         | Low         | Complete with error flag         |
| WorkflowCompletionWorker | Critical    | BPMN error → creates incident    |
| NotificationWorker       | Low         | Complete with failure flag       |

### Global Exception Handler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                   .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflow(WorkflowException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                   .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(TaskOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleTask(TaskOperationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                   .body(ApiResponse.error(ex.getMessage()));
    }
}
```

---

## 8. Key Design Patterns

### 8.1 External Task Pattern

Decouples business logic from workflow engine for scalability and resilience.

### 8.2 Repository Pattern

JPA repositories abstract data access:

```java
public interface RegulatoryRequestRepository extends JpaRepository<RegulatoryRequest, UUID> {
    List<RegulatoryRequest> findBySubmitterId(String submitterId);
    List<RegulatoryRequest> findByStatus(ApprovalStatus status);
    Optional<RegulatoryRequest> findByProcessInstanceId(String processInstanceId);
}
```

### 8.3 Builder Pattern

DTOs use Lombok's `@Builder` for clean object construction:

```java
WorkflowResponse response = WorkflowResponse.builder()
    .requestId(request.getId())
    .processInstanceId(request.getProcessInstanceId())
    .status(request.getStatus().name())
    .build();
```

### 8.4 Strategy Pattern

Different fallback strategies for different worker types based on criticality.

### 8.5 Observer Pattern

Camunda execution/task listeners observe workflow events:

```java
@Component
public class TaskAuditListener implements TaskListener {
    @Override
    public void notify(DelegateTask delegateTask) {
        auditService.recordAuditEvent(...);
    }
}
```

### 8.6 Filter Chain Pattern

Spring Security filter chain for authentication:

```txt
Request → JwtAuthenticationFilter → SecurityContext → Controller
```

---

## Technology Stack Summary

| Component       | Technology            | Version  |
| --------------- | --------------------- | -------- |
| Runtime         | Java                  | 21 (LTS) |
| Framework       | Spring Boot           | 3.5.9    |
| Workflow Engine | Camunda BPM           | 7.22.0   |
| Database        | H2 (dev)              | 2.3.x    |
| Security        | Spring Security + JWT | 6.x      |
| API Docs        | SpringDoc OpenAPI     | 2.8.0    |
| Build           | Maven                 | 3.9+     |

---

## Quick Start

```bash
# Build and run
mvn clean install
mvn spring-boot:run

# Access points
# API:       http://localhost:8080/swagger-ui.html
# Camunda:   http://localhost:8080/camunda (admin/admin)
# H2:        http://localhost:8080/h2-console (sa/empty)
# Health:    http://localhost:8080/api/v1/health
```

---

## File Structure Overview

```txt
src/main/java/com/enterprise/regulatory/
├── config/          # Spring & Camunda configuration
├── controller/      # REST API endpoints
├── service/         # Business logic orchestration
├── worker/          # External task workers
├── listener/        # Camunda event listeners
├── security/        # JWT & authentication
├── model/           # Entities & enums
├── dto/             # Request/Response objects
├── repository/      # Data access
└── exception/       # Error handling

src/main/resources/
├── bpmn/            # BPMN process definitions
├── static/forms/    # Camunda Tasklist forms
└── application.yml  # Configuration
```
