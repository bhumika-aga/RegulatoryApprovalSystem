# Regulatory Approval System - Architecture Documentation

## Executive Summary

This document provides a comprehensive architectural overview of the Regulatory Approval System, an enterprise-grade
BPMN-based workflow application built with Spring Boot 3 and Camunda 7. The system simulates regulatory approval
workflows typical in BFSI (Banking, Financial Services, and Insurance) and healthcare domains.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architectural Patterns](#2-architectural-patterns)
3. [Technology Stack](#3-technology-stack)
4. [Component Architecture](#4-component-architecture)
5. [BPMN Workflow Design](#5-bpmn-workflow-design)
6. [Security Architecture](#6-security-architecture)
7. [External Task Worker Pattern](#7-external-task-worker-pattern)
8. [Audit Trail Design](#8-audit-trail-design)
9. [Database Schema](#9-database-schema)
10. [API Design](#10-api-design)
11. [Error Handling Strategy](#11-error-handling-strategy)
12. [Deployment Architecture](#12-deployment-architecture)

---

## 1. System Overview

### 1.1 Business Context

The Regulatory Approval System automates multi-stage approval workflows with:

- **Human Task Management**: User tasks assigned to role-based candidate groups
- **SLA Enforcement**: Timer boundary events trigger escalations on timeout
- **Compliance Validation**: External task workers perform automated checks
- **Full Auditability**: Every workflow event is recorded for regulatory compliance

### 1.2 High-Level Architecture

```txt
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT LAYER                                    │
│         (Web Applications, Mobile Apps, Third-party Integrations)           │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           API GATEWAY LAYER                                  │
│                     (JWT Authentication, Rate Limiting)                      │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION LAYER                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      REST Controllers                                │   │
│  │  AuthController │ WorkflowController │ TaskController │ AuditController│ │
│  └─────────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      Service Layer                                   │   │
│  │     WorkflowService    │    WorkflowTaskService    │   AuditService  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
┌──────────────────────────────┐    ┌──────────────────────────────────────────┐
│   CAMUNDA BPM ENGINE LAYER   │    │        EXTERNAL TASK WORKERS             │
│  ┌────────────────────────┐  │    │  ┌──────────────────────────────────┐   │
│  │  Process Runtime       │  │    │  │  ComplianceCheckWorker           │   │
│  │  - RuntimeService      │  │    │  │  - Polls "compliance-check" topic│   │
│  │  - TaskService         │  │    │  │  - Validates regulatory rules    │   │
│  │  - HistoryService      │  │    │  │  - Returns PASS/FAIL/REVIEW      │   │
│  └────────────────────────┘  │    │  └──────────────────────────────────┘   │
│  ┌────────────────────────┐  │    │  ┌──────────────────────────────────┐   │
│  │  Listeners             │  │    │  │  RiskScoringWorker               │   │
│  │  - TaskAuditListener   │  │    │  │  - Polls "risk-scoring" topic    │   │
│  │  - WorkflowListeners   │  │    │  │  - Calculates risk score         │   │
│  └────────────────────────┘  │    │  └──────────────────────────────────┘   │
│                              │    │  ┌──────────────────────────────────┐   │
│                              │    │  │  EscalationWorker                │   │
│                              │    │  │  - Polls "escalation-handler"    │   │
│                              │    │  │  - Handles SLA breach escalation │   │
│                              │    │  └──────────────────────────────────┘   │
│                              │    │  ┌──────────────────────────────────┐   │
│                              │    │  │  WorkflowCompletionWorker        │   │
│                              │    │  │  - Polls "workflow-completion"   │   │
│                              │    │  │  - Finalizes approval/rejection  │   │
│                              │    │  └──────────────────────────────────┘   │
│                              │    │  ┌──────────────────────────────────┐   │
│                              │    │  │  NotificationWorker              │   │
│                              │    │  │  - Polls "notification-service"  │   │
│                              │    │  │  - Sends email/Slack alerts      │   │
│                              │    │  └──────────────────────────────────┘   │
└──────────────────────────────┘    └──────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PERSISTENCE LAYER                                    │
│  ┌────────────────────┐  ┌────────────────────┐  ┌────────────────────┐    │
│  │  Camunda Tables    │  │  Regulatory        │  │  Audit Trail       │    │
│  │  (ACT_RU_*, etc.)  │  │  Request Table     │  │  Table             │    │
│  └────────────────────┘  └────────────────────┘  └────────────────────┘    │
│                           PostgreSQL Database                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Architectural Patterns

### 2.1 Layered Architecture

The system follows a strict layered architecture:

| Layer            | Responsibility                               | Components             |
|------------------|----------------------------------------------|------------------------|
| **Presentation** | API endpoints, request/response handling     | Controllers, DTOs      |
| **Business**     | Core business logic, workflow orchestration  | Services               |
| **Integration**  | Camunda engine interaction, external systems | Delegates, Workers     |
| **Persistence**  | Data access and storage                      | Repositories, Entities |

### 2.2 External Task Pattern

Instead of embedding service logic within the workflow engine, we use **External Task Workers**:

```txt
┌──────────────────┐                      ┌──────────────────┐
│  Camunda Engine  │                      │  External Worker │
│                  │  1. Poll for tasks   │                  │
│  External Task   │ ◄─────────────────── │                  │
│  Topic: X        │                      │                  │
│                  │  2. Fetch & Lock     │                  │
│                  │ ─────────────────────►│                  │
│                  │                      │  3. Execute      │
│                  │                      │     Business     │
│                  │  4. Complete with    │     Logic        │
│                  │     variables        │                  │
│                  │ ◄─────────────────── │                  │
└──────────────────┘                      └──────────────────┘
```

**Benefits:**

1. **Decoupling**: Workflow engine is independent of business logic
2. **Scalability**: Workers can scale horizontally
3. **Polyglot**: Workers can be written in any language
4. **Resilience**: Built-in retry and incident handling

### 2.3 Event-Driven Audit

All workflow events trigger audit records through Camunda listeners:

```java
// TaskListener captures task lifecycle events
@Override
public void notify(DelegateTask task) {
    switch (task.getEventName()) {
        case EVENTNAME_CREATE -> recordTaskCreated(task);
        case EVENTNAME_ASSIGNMENT -> recordTaskClaimed(task);
        case EVENTNAME_COMPLETE -> recordTaskCompleted(task);
    }
}
```

---

## 3. Technology Stack

| Component | Technology      | Version | Purpose                          |
|-----------|-----------------|---------|----------------------------------|
| Runtime   | Java            | 21      | LTS with virtual threads support |
| Framework | Spring Boot     | 3.2.1   | Application framework            |
| Workflow  | Camunda BPM     | 7.20.0  | BPMN 2.0 engine                  |
| Database  | PostgreSQL      | 15+     | Persistent storage               |
| Security  | Spring Security | 6.x     | Authentication/Authorization     |
| JWT       | jjwt            | 0.12.3  | Token generation/validation      |
| Migration | Flyway          | 10.4.1  | Database versioning              |
| API Docs  | SpringDoc       | 2.3.0   | OpenAPI 3.0 documentation        |
| Build     | Maven           | 3.9+    | Dependency management            |

---

## 4. Component Architecture

### 4.1 Package Structure

```txt
com.enterprise.regulatory/
├── config/                          # Configuration
│   ├── SecurityConfig.java          # Spring Security setup
│   ├── CamundaConfig.java           # Camunda engine beans
│   ├── OpenApiConfig.java           # Swagger configuration
│   └── AsyncConfig.java             # Async execution pools
│
├── controller/                      # REST API Layer
│   ├── AuthController.java          # JWT token generation
│   ├── WorkflowController.java      # Workflow lifecycle APIs
│   ├── TaskController.java          # Task management APIs
│   ├── AuditController.java         # Audit trail queries
│   └── HealthController.java        # Health checks
│
├── service/                         # Business Logic Layer
│   ├── WorkflowService.java         # Workflow operations
│   ├── WorkflowTaskService.java     # Task operations
│   └── AuditService.java            # Audit recording
│
├── listener/                        # Camunda Listeners
│   ├── TaskAuditListener.java       # Task event auditing
│   ├── WorkflowStartListener.java   # Process start auditing
│   └── WorkflowEndListener.java     # Process end auditing
│
├── worker/                          # External Task Workers
│   ├── ExternalTaskWorkerConfig.java  # Worker client config
│   ├── ComplianceCheckWorker.java   # Compliance validation
│   ├── RiskScoringWorker.java       # Risk assessment
│   ├── EscalationWorker.java        # SLA breach handling
│   ├── WorkflowCompletionWorker.java # Workflow finalization
│   └── NotificationWorker.java      # Notification dispatch
│
├── security/                        # Security Components
│   ├── JwtTokenProvider.java        # JWT creation/validation
│   ├── JwtAuthenticationFilter.java # Request authentication
│   ├── JwtProperties.java           # JWT configuration
│   ├── UserPrincipal.java           # Security principal
│   ├── SecurityUtils.java           # Security utilities
│   ├── CamundaIdentityService.java  # JWT-Camunda sync
│   └── JwtAuthenticationEntryPoint.java  # Auth error handling
│
├── model/                           # Domain Model
│   ├── entity/
│   │   ├── RegulatoryRequest.java   # Main business entity
│   │   └── WorkflowAudit.java       # Audit record entity
│   └── enums/
│       ├── ApprovalStatus.java      # Workflow states
│       ├── AuditEventType.java      # Audit event types
│       ├── ComplianceResult.java    # Compliance outcomes
│       └── UserRole.java            # System roles
│
├── dto/                             # Data Transfer Objects
│   ├── request/
│   │   ├── StartWorkflowRequest.java
│   │   ├── CompleteTaskRequest.java
│   │   └── AuthRequest.java
│   └── response/
│       ├── WorkflowResponse.java
│       ├── TaskResponse.java
│       ├── AuditResponse.java
│       ├── AuthResponse.java
│       └── ApiResponse.java
│
├── repository/                      # Data Access Layer
│   ├── RegulatoryRequestRepository.java
│   └── WorkflowAuditRepository.java
│
├── exception/                       # Exception Handling
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java
│   ├── WorkflowException.java
│   └── TaskOperationException.java
│
└── RegulatoryApprovalApplication.java  # Main entry point
```

### 4.2 Component Dependencies

```txt
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Controller  │────►│   Service   │────►│ Repository  │
└─────────────┘     └─────────────┘     └─────────────┘
       │                   │
       │                   ▼
       │            ┌─────────────┐
       │            │  Camunda    │
       │            │  Services   │
       │            └─────────────┘
       │                   │
       ▼                   ▼
┌─────────────┐     ┌─────────────┐
│    DTO      │     │  Listener   │
│  (Request/  │     │   Worker    │
│  Response)  │     │             │
└─────────────┘     └─────────────┘
```

---

## 5. BPMN Workflow Design

### 5.1 Process Flow

```txt
┌─────────────┐
│   START     │
│  Request    │
│  Submitted  │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│   Risk      │  External Task
│  Scoring    │  Topic: risk-scoring
└──────┬──────┘
       │
       ▼
┌─────────────┐  Timer: PT8H
│  Initial    │─────────────────►┌─────────────┐
│  Review     │                  │  Escalate   │
│ (REVIEWER)  │                  │  to MANAGER │
└──────┬──────┘                  └─────────────┘
       │
       ▼ APPROVED
┌─────────────┐  Timer: PT24H
│  Manager    │─────────────────►┌─────────────┐
│  Approval   │                  │  Escalate   │
│ (MANAGER)   │                  │  to SR_MGR  │
└──────┬──────┘                  └─────────────┘
       │
       ├──────► ESCALATE ──────►┌─────────────┐
       │                        │Senior Mgr   │
       │                        │  Review     │
       │                        └──────┬──────┘
       │                               │
       ▼ APPROVED ◄────────────────────┘
┌─────────────┐  Timer: PT48H
│ Compliance  │  External Task
│   Check     │  Topic: compliance-check
└──────┬──────┘
       │
       ├──────► REQUIRES_REVIEW ►┌─────────────┐
       │                         │ Manual      │
       │                         │ Compliance  │
       │                         │ Review      │
       │                         └──────┬──────┘
       │                                │
       ▼ PASS ◄─────────────────────────┘
┌─────────────┐  Timer: PT8H
│   Final     │─────────────────►┌─────────────┐
│  Approval   │                  │  Escalate   │
│(ADMIN/SR_MGR)                  │  to ADMIN   │
└──────┬──────┘                  └─────────────┘
       │
       ├──────► REJECTED ────────►┌─────────────┐
       │                          │   END       │
       │                          │  Rejected   │
       ▼ APPROVED                 └─────────────┘
┌─────────────┐
│    END      │
│  Approved   │
└─────────────┘
```

### 5.2 SLA Configuration

| Stage            | Duration | Escalation Target | BPMN Timer |
|------------------|----------|-------------------|------------|
| Initial Review   | 8 hours  | MANAGER           | `PT8H`     |
| Manager Approval | 24 hours | SENIOR_MANAGER    | `PT24H`    |
| Compliance Check | 48 hours | COMPLIANCE Lead   | `PT48H`    |
| Final Approval   | 8 hours  | ADMIN             | `PT8H`     |

### 5.3 Timer Boundary Event

```xml

<bpmn:boundaryEvent id="Timer_InitialReviewSLA" attachedToRef="Task_InitialReview"
                    cancelActivity="false">
    <bpmn:outgoing>Flow_ReviewerEscalation</bpmn:outgoing>
    <bpmn:timerEventDefinition>
        <bpmn:timeDuration>PT8H</bpmn:timeDuration>
    </bpmn:timerEventDefinition>
</bpmn:boundaryEvent>
```

**Key Design Decision**: `cancelActivity="false"` ensures the original task remains active while the escalation path
executes. This allows the original assignee to complete the task even after escalation notification is sent.

---

## 6. Security Architecture

### 6.1 JWT Authentication Flow

```txt
┌────────────┐    1. POST /auth/token     ┌────────────┐
│   Client   │ ───────────────────────────►│   Auth     │
│            │    {username, roles}        │ Controller │
│            │                             │            │
│            │ ◄─────────────────────────── │            │
│            │    2. JWT Token             │            │
└────────────┘                             └────────────┘
       │
       │  3. Request with Authorization: Bearer <token>
       ▼
┌────────────┐    4. Validate JWT          ┌────────────┐
│   JWT      │ ───────────────────────────►│  Security  │
│   Filter   │                             │  Context   │
│            │    5. Set Authentication    │            │
│            │ ───────────────────────────►│            │
└────────────┘                             └────────────┘
       │
       │  6. Sync roles to Camunda
       ▼
┌────────────┐
│  Camunda   │
│  Identity  │
│  Service   │
└────────────┘
```

### 6.2 JWT Token Structure

```json
{
  "sub": "john.doe",
  "roles": [
    "REVIEWER",
    "MANAGER"
  ],
  "department": "RISK",
  "iss": "regulatory-approval-system",
  "iat": 1704067200,
  "exp": 1704153600
}
```

### 6.3 Role Hierarchy

| Role             | Description            | Permissions                                |
|------------------|------------------------|--------------------------------------------|
| `REVIEWER`       | Initial assessment     | Start workflow, Initial Review tasks       |
| `MANAGER`        | Business approval      | Manager Approval tasks, view team requests |
| `SENIOR_MANAGER` | Escalation handling    | Handle escalations, Final Approval         |
| `COMPLIANCE`     | Regulatory validation  | Compliance Review tasks                    |
| `AUDITOR`        | Read-only audit access | View audit trails only                     |
| `ADMIN`          | Full system access     | All operations, terminate workflows        |

### 6.4 Endpoint Security Matrix

| Endpoint                 | Method | Required Roles             |
|--------------------------|--------|----------------------------|
| `/api/v1/auth/**`        | *      | Public                     |
| `/api/v1/workflow/start` | POST   | REVIEWER, MANAGER, ADMIN   |
| `/api/v1/workflow/**`    | *      | Authenticated              |
| `/api/v1/tasks/**`       | *      | Authenticated              |
| `/api/v1/audit/**`       | *      | AUDITOR, ADMIN, COMPLIANCE |
| `/api/v1/admin/**`       | *      | ADMIN                      |

---

## 7. External Task Worker Pattern

### 7.1 Worker Architecture

```java

@Component
public class ComplianceCheckWorker implements ExternalTaskHandler {
    
    private static final String TOPIC_NAME = "compliance-check";
    
    @PostConstruct
    public void subscribe() {
        externalTaskClient.subscribe(TOPIC_NAME)
            .lockDuration(60000)
            .handler(this)
            .open();
    }
    
    @Override
    public void execute(ExternalTask task, ExternalTaskService service) {
        try {
            // 1. Extract variables
            String requestId = task.getVariable("requestId");
            
            // 2. Execute business logic
            ComplianceResult result = performComplianceCheck(requestId);
            
            // 3. Complete with result variables
            Map<String, Object> variables = Map.of(
                "complianceResult", result.name()
            );
            service.complete(task, variables);
        
        } catch (Exception e) {
            // 4. Handle failure with retry
            handleFailure(task, service, e);
        }
    }
}
```

### 7.2 Failure Handling

```java
private void handleFailure(ExternalTask task, ExternalTaskService service, Exception e) {
    int retries = task.getRetries() != null ? task.getRetries() : MAX_RETRIES;
    
    if (retries > 0) {
        // Retry with exponential backoff
        service.handleFailure(task,
            e.getMessage(),
            e.getClass().getName(),
            retries - 1,
            RETRY_TIMEOUT * (MAX_RETRIES - retries + 1));
    } else {
        // Create incident for manual intervention
        service.handleBpmnError(task, "ERROR_CODE", e.getMessage());
    }
}
```

### 7.3 External Tasks in BPMN

```xml

<bpmn:serviceTask id="Task_ComplianceCheck" name="Compliance Check"
                  camunda:type="external"
                  camunda:topic="compliance-check">
    <bpmn:extensionElements>
        <camunda:inputOutput>
            <camunda:inputParameter name="requestId">${requestId}</camunda:inputParameter>
            <camunda:inputParameter name="requestType">${requestType}</camunda:inputParameter>
        </camunda:inputOutput>
    </bpmn:extensionElements>
</bpmn:serviceTask>
```

---

## 8. Audit Trail Design

### 8.1 Audit Event Types

```java
public enum AuditEventType {
    WORKFLOW_STARTED,      // Process instance created
    TASK_CREATED,          // User task created
    TASK_CLAIMED,          // Task assigned to user
    TASK_COMPLETED,        // Task completed with decision
    TASK_ESCALATED,        // SLA breach triggered escalation
    TASK_REASSIGNED,       // Task moved to different user/group
    DECISION_MADE,         // Approval/Rejection recorded
    COMPLIANCE_CHECK_PASSED,
    COMPLIANCE_CHECK_FAILED,
    SLA_BREACH,            // Timer boundary event fired
    WORKFLOW_COMPLETED,    // Process ended successfully
    WORKFLOW_TERMINATED    // Process manually terminated
}
```

### 8.2 Hybrid Audit Model

**Task-Level Events (Always Captured)**:

- Task created, claimed, completed, escalated
- Workflow started, completed, terminated

**Field-Level Changes (Selectively Captured)**:

- `reviewerDecision`: APPROVED/REJECTED
- `managerDecision`: APPROVED/REJECTED/ESCALATE
- `complianceResult`: PASS/FAIL
- `finalDecision`: APPROVED/REJECTED
- `rejectionReason`: Text explanation

### 8.3 Audit Table Schema

```sql
CREATE TABLE workflow_audit
(
    id                     UUID PRIMARY KEY,
    process_instance_id    VARCHAR(64)  NOT NULL,
    process_definition_key VARCHAR(255),
    task_id                VARCHAR(64),
    task_name              VARCHAR(255),
    event_type             VARCHAR(50)  NOT NULL,
    old_value              TEXT,
    new_value              TEXT,
    performed_by           VARCHAR(100) NOT NULL,
    role                   VARCHAR(50),
    comment                TEXT,
    timestamp              TIMESTAMP    NOT NULL,
    ip_address             VARCHAR(45),
    additional_data        TEXT
);
```

---

## 9. Database Schema

### 9.1 Entity Relationship Diagram

```txt
┌─────────────────────────┐
│   regulatory_request    │
├─────────────────────────┤
│ id (PK)                 │
│ process_instance_id (UK)│───────┐
│ request_title           │       │
│ request_type            │       │
│ department              │       │
│ status                  │       │
│ submitter_id            │       │
│ current_assignee        │       │
│ current_stage           │       │
│ reviewer_decision       │       │
│ manager_decision        │       │
│ compliance_result       │       │
│ final_decision          │       │
│ risk_score              │       │
│ escalated               │       │
│ created_at              │       │
│ completed_at            │       │
└─────────────────────────┘       │
                                  │
┌─────────────────────────┐       │
│    workflow_audit       │       │
├─────────────────────────┤       │
│ id (PK)                 │       │
│ process_instance_id (FK)│◄──────┘
│ task_id                 │
│ task_name               │
│ event_type              │
│ old_value               │
│ new_value               │
│ performed_by            │
│ role                    │
│ comment                 │
│ timestamp               │
└─────────────────────────┘

┌─────────────────────────┐
│   sla_configuration     │
├─────────────────────────┤
│ id (PK)                 │
│ stage_name (UK)         │
│ sla_duration_hours      │
│ escalation_target_role  │
│ notification_before_hrs │
│ active                  │
└─────────────────────────┘
```

### 9.2 Camunda Tables

Camunda maintains its own tables (prefix `ACT_`):

- `ACT_RU_*`: Runtime tables (active process instances, tasks)
- `ACT_HI_*`: History tables (completed processes, audit)
- `ACT_RE_*`: Repository tables (process definitions)
- `ACT_ID_*`: Identity tables (users, groups - not used with JWT)

---

## 10. API Design

### 10.1 RESTful Endpoints

| Endpoint                       | Method | Description                  |
|--------------------------------|--------|------------------------------|
| `/api/v1/auth/token`           | POST   | Generate JWT token           |
| `/api/v1/auth/refresh`         | POST   | Refresh expired token        |
| `/api/v1/workflow/start`       | POST   | Start new workflow           |
| `/api/v1/workflow/status/{id}` | GET    | Get workflow status          |
| `/api/v1/workflow/{id}`        | GET    | Get workflow by ID           |
| `/api/v1/workflow/my-requests` | GET    | Get current user's workflows |
| `/api/v1/workflow/escalated`   | GET    | Get escalated workflows      |
| `/api/v1/workflow/{id}`        | DELETE | Terminate workflow           |
| `/api/v1/tasks`                | GET    | Get tasks for current user   |
| `/api/v1/tasks/{id}`           | GET    | Get task details             |
| `/api/v1/tasks/{id}/claim`     | POST   | Claim a task                 |
| `/api/v1/tasks/{id}/unclaim`   | POST   | Release a task               |
| `/api/v1/tasks/{id}/complete`  | POST   | Complete task with decision  |
| `/api/v1/audit/process/{id}`   | GET    | Get audit trail for process  |
| `/api/v1/audit/user/{id}`      | GET    | Get audit trail by user      |
| `/api/v1/audit/sla-breaches`   | GET    | Get SLA breach records       |

### 10.2 Request/Response Examples

**Start Workflow**:

```json
// POST /api/v1/workflow/start
{
  "requestTitle": "New Investment Product Launch",
  "requestDescription": "Approval for new mutual fund product",
  "requestType": "FINANCIAL_PRODUCT",
  "department": "INVESTMENT",
  "priority": "HIGH"
}

// Response
{
  "success": true,
  "data": {
    "requestId": "550e8400-e29b-41d4-a716-446655440000",
    "processInstanceId": "12345",
    "status": "IN_REVIEW",
    "currentStage": "INITIAL_REVIEW"
  }
}
```

**Complete Task**:

```json
// POST /api/v1/tasks/{taskId}/complete
{
  "decision": "APPROVED",
  "comment": "Requirements verified, approved for next stage"
}
```

---

## 11. Error Handling Strategy

### 11.1 Exception Hierarchy

```txt
RuntimeException
├── ResourceNotFoundException (404)
├── WorkflowException (400)
├── TaskOperationException (409)
└── AccessDeniedException (403)
```

### 11.2 Global Exception Handler

```java

@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                   .body(ApiResponse.error(ex.getMessage()));
    }
    
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(
        MethodArgumentNotValidException ex) {
        Map<String, String> errors = extractValidationErrors(ex);
        return ResponseEntity.badRequest()
                   .body(ApiResponse.error("Validation failed", errors));
    }
}
```

### 11.3 API Response Format

```json
{
  "success": false,
  "message": "Task not found with ID: abc123",
  "data": null,
  "timestamp": "2024-01-15T10:30:00",
  "path": "/api/v1/tasks/abc123"
}
```

---

## 12. Deployment Architecture

### 12.1 Docker Compose Setup

```yaml
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: regulatory_db
    volumes:
      - postgres_data:/var/lib/postgresql/data

  app:
    build: .
    environment:
      DB_HOST: postgres
      JWT_SECRET: ${JWT_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
```

### 12.2 Production Considerations

1. **Database**: Use managed PostgreSQL (AWS RDS, Azure Database)
2. **Secrets**: Store JWT secrets in vault (HashiCorp Vault, AWS Secrets Manager)
3. **Scaling**: External task workers can scale independently
4. **Monitoring**: Integrate with Prometheus/Grafana for metrics
5. **Logging**: Centralized logging with ELK stack

### 12.3 Health Checks

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when_authorized
```

---

## Appendix A: Key Design Decisions

| Decision                  | Rationale                                             |
|---------------------------|-------------------------------------------------------|
| External Task Workers     | Decouples business logic from engine, enables scaling |
| JWT over Camunda Identity | Centralizes auth, avoids duplicate identity storage   |
| Hybrid Audit Model        | Balances completeness with storage efficiency         |
| Non-canceling timers      | Allows original assignee to complete after escalation |
| PostgreSQL                | Production-grade RDBMS, Camunda native support        |

## Appendix B: References

- [Camunda 7 Documentation](https://docs.camunda.org/manual/7.20/)
- [Spring Boot 3 Documentation](https://docs.spring.io/spring-boot/docs/3.2.x/reference/html/)
- [BPMN 2.0 Specification](https://www.omg.org/spec/BPMN/2.0/)
- [JWT RFC 7519](https://datatracker.ietf.org/doc/html/rfc7519)
