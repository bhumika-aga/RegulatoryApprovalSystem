# Implementation Guide - Regulatory Approval System

This document explains the implementation details, design decisions, and core concepts behind the Regulatory Approval
System.

## Table of Contents

1. [Overview](#1-overview)
2. [External Task Workers Architecture](#2-external-task-workers-architecture)
3. [BPMN Workflow Implementation](#3-bpmn-workflow-implementation)
4. [Security Implementation](#4-security-implementation)
5. [Audit Trail Implementation](#5-audit-trail-implementation)
6. [Error Handling & Fallbacks](#6-error-handling--fallbacks)
7. [Key Design Decisions](#7-key-design-decisions)
8. [Camunda Tasklist Forms](#8-camunda-tasklist-forms)
9. [Database Configuration](#9-database-configuration)
10. [Version Compatibility](#10-version-compatibility)
11. [Testing Guide](#11-testing-guide)

---

## 1. Overview

The Regulatory Approval System is an enterprise-grade BPMN-based workflow application that implements a multi-stage
approval process using **only External Task Workers** (no Java Delegates). This architecture provides maximum
decoupling, scalability, and resilience.

### Why External Workers Only?

| Aspect         | Java Delegates            | External Workers         |
| -------------- | ------------------------- | ------------------------ |
| **Coupling**   | Tightly coupled to engine | Fully decoupled          |
| **Scaling**    | Scale with engine         | Scale independently      |
| **Language**   | Java only                 | Any language             |
| **Failure**    | Blocks workflow           | Automatic retry/incident |
| **Deployment** | Redeploy engine           | Deploy independently     |

---

## 2. External Task Workers Architecture

### 2.1 Worker Topics

The system uses 5 external task topics:

| Topic                  | Worker                   | Purpose                              |
| ---------------------- | ------------------------ | ------------------------------------ |
| `risk-scoring`         | RiskScoringWorker        | Calculate risk score for requests    |
| `compliance-check`     | ComplianceCheckWorker    | Validate regulatory compliance       |
| `escalation-handler`   | EscalationWorker         | Handle SLA breach escalations        |
| `workflow-completion`  | WorkflowCompletionWorker | Finalize workflow approval/rejection |
| `notification-service` | NotificationWorker       | Send notifications                   |

### 2.2 Worker Pattern Implementation

Each worker follows a consistent pattern:

```java

@Component
public class ExampleWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "example-topic";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 5000L;

    private final ExternalTaskClient client;
    private TopicSubscription subscription;

    @PostConstruct
    public void subscribe() {
        subscription = client.subscribe(TOPIC_NAME)
                           .lockDuration(30000)    // Lock task for 30 seconds
                           .handler(this)
                           .open();
    }

    @PreDestroy
    public void unsubscribe() {
        if (subscription != null) {
            subscription.close();
        }
    }

    @Override
    public void execute(ExternalTask task, ExternalTaskService service) {
        try {
            // 1. Extract input variables
            String inputVar = task.getVariable("inputVar");

            // 2. Execute business logic
            String result = processTask(inputVar);

            // 3. Complete with output variables
            Map<String, Object> variables = new HashMap<>();
            variables.put("outputVar", result);
            service.complete(task, variables);

        } catch (Exception e) {
            handleFailure(task, service, e);
        }
    }

    private void handleFailure(ExternalTask task,
                               ExternalTaskService service,
                               Exception e) {
        int retries = task.getRetries() != null
            ? task.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            // Retry with backoff
            service.handleFailure(task,
                e.getMessage(),
                e.getClass().getName(),
                retries - 1,
                RETRY_TIMEOUT);
        } else {
            // Create incident or complete with error
            service.handleBpmnError(task, "ERROR_CODE", e.getMessage());
        }
    }
}
```

### 2.3 EscalationWorker

Handles SLA breach escalation:

```txt
Timer Fires → BPMN triggers external task → EscalationWorker picks up
                                                    ↓
                                          Update request as escalated
                                                    ↓
                                          Record audit events
                                                    ↓
                                          Set escalation variables
                                                    ↓
                                          Complete task → Notification sent
```

**Key Logic:**

- Receives: `escalationTarget`, `originalTask`, `requestId`
- Updates: `RegulatoryRequest.escalated = true`
- Records: `SLA_BREACH` and `TASK_ESCALATED` audit events
- Returns: `escalated=true`, `escalationTimestamp`, `escalationReason`

### 2.4 WorkflowCompletionWorker

Finalizes workflow with approval or rejection:

```txt
Final Decision → BPMN triggers external task → WorkflowCompletionWorker
                                                        ↓
                                              Determine final status
                                                        ↓
                                              Update request entity
                                                        ↓
                                              Build rejection reason (if rejected)
                                                        ↓
                                              Record completion audit
                                                        ↓
                                              Complete → Notification sent
```

**Key Logic:**

- Receives: `outcome` (APPROVED/REJECTED), `requestId`
- Updates: `RegulatoryRequest.status`, `finalDecision`, `completedAt`
- Builds: Comprehensive rejection reason from all stages
- Records: `WORKFLOW_COMPLETED` and `DECISION_MADE` audit events

---

## 3. BPMN Workflow Implementation

### 3.1 Process Variables

| Variable           | Type    | Set By               | Used By                             |
| ------------------ | ------- | -------------------- | ----------------------------------- |
| `requestId`        | UUID    | WorkflowService      | All workers                         |
| `requestTitle`     | String  | StartWorkflowRequest | NotificationWorker                  |
| `requestType`      | String  | StartWorkflowRequest | RiskScoringWorker, ComplianceWorker |
| `department`       | String  | StartWorkflowRequest | RiskScoringWorker                   |
| `submitterId`      | String  | SecurityContext      | Task assignment                     |
| `riskScore`        | Integer | RiskScoringWorker    | ComplianceWorker                    |
| `reviewerDecision` | String  | User task            | Gateway routing                     |
| `managerDecision`  | String  | User task            | Gateway routing                     |
| `complianceResult` | String  | ComplianceWorker     | Gateway routing                     |
| `finalDecision`    | String  | User task            | WorkflowCompletionWorker            |
| `escalated`        | Boolean | EscalationWorker     | Tracking                            |
| `outcome`          | String  | BPMN input           | WorkflowCompletionWorker            |

### 3.2 Timer Boundary Events

Non-canceling timers allow original task to continue while triggering escalation:

```xml

<bpmn:boundaryEvent id="Timer_InitialReviewSLA"
                    attachedToRef="Task_InitialReview"
                    cancelActivity="false">  <!-- Key: false = non-canceling -->
    <bpmn:timerEventDefinition>
        <bpmn:timeDuration>PT8H</bpmn:timeDuration>
    </bpmn:timerEventDefinition>
</bpmn:boundaryEvent>
```

**Why non-canceling?**

- Original assignee can still complete the task
- Escalation runs as parallel flow
- Both paths can complete independently

### 3.3 External Task Configuration in BPMN

```xml

<bpmn:serviceTask id="Task_EscalateInitialReview"
                  name="Escalate to Manager"
                  camunda:type="external"
                  camunda:topic="escalation-handler">
    <bpmn:extensionElements>
        <camunda:inputOutput>
            <!-- Input parameters passed to worker -->
            <camunda:inputParameter name="escalationTarget">MANAGER</camunda:inputParameter>
            <camunda:inputParameter name="originalTask">InitialReview</camunda:inputParameter>
            <camunda:inputParameter name="requestId">${requestId}</camunda:inputParameter>
        </camunda:inputOutput>
    </bpmn:extensionElements>
</bpmn:serviceTask>
```

---

## 4. Security Implementation

### 4.1 JWT Authentication Flow

```txt
1. Client → POST /api/v1/auth/token {username, roles, department}
                    ↓
2. AuthController → JwtTokenProvider.generateToken()
                    ↓
3. JWT returned to client
                    ↓
4. Client → Request with Authorization: Bearer <token>
                    ↓
5. JwtAuthenticationFilter → Validate token
                    ↓
6. Set SecurityContextHolder.authentication
                    ↓
7. CamundaIdentityService → Sync groups to Camunda
```

### 4.2 Role-Based Task Assignment

```java
// BPMN assigns tasks to candidate groups
camunda:candidateGroups="REVIEWER"

// JWT roles are synced to Camunda groups
    identityService.

setAuthenticatedUserId(username);
for(
String role :roles){
    identityService.

createMembership(username, role);
}

// Task query filters by group membership
    taskService.

createTaskQuery()
    .

taskCandidateGroupIn(userGroups)
    .

list();
```

---

## 5. Audit Trail Implementation

### 5.1 Audit Recording Points

| Event               | Trigger              | Component                                     |
| ------------------- | -------------------- | --------------------------------------------- |
| WORKFLOW_STARTED    | Process start        | WorkflowStartListener                         |
| TASK_CREATED        | User task created    | TaskAuditListener                             |
| TASK_CLAIMED        | Task assigned        | TaskAuditListener                             |
| TASK_COMPLETED      | Task completed       | TaskAuditListener                             |
| SLA_BREACH          | Timer fires          | EscalationWorker                              |
| TASK_ESCALATED      | Escalation processed | EscalationWorker                              |
| COMPLIANCE*CHECK*\* | Compliance result    | ComplianceCheckWorker                         |
| DECISION_MADE       | Final decision       | WorkflowCompletionWorker                      |
| WORKFLOW_COMPLETED  | Process ends         | WorkflowCompletionWorker, WorkflowEndListener |

### 5.2 Audit Service Pattern

```java

@Service
public class AuditService {

    public void recordAuditEvent(
        String processInstanceId,
        String taskId,
        String taskName,
        AuditEventType eventType,
        String oldValue,
        String newValue,
        String performedBy,
        String role,
        String comment) {

        WorkflowAudit audit = WorkflowAudit.builder()
                                  .id(UUID.randomUUID())
                                  .processInstanceId(processInstanceId)
                                  .taskId(taskId)
                                  .taskName(taskName)
                                  .eventType(eventType)
                                  .oldValue(oldValue)
                                  .newValue(newValue)
                                  .performedBy(performedBy)
                                  .role(role)
                                  .comment(comment)
                                  .timestamp(LocalDateTime.now())
                                  .build();

        auditRepository.save(audit);
    }
}
```

---

## 6. Error Handling & Fallbacks

### 6.1 Worker Retry Strategy

```java
private void handleFailure(ExternalTask task,
                           ExternalTaskService service,
                           Exception e) {
    int retries = task.getRetries() != null ? task.getRetries() : MAX_RETRIES;

    if (retries > 0) {
        // Exponential backoff: 5s, 10s, 15s
        long backoff = RETRY_TIMEOUT * (MAX_RETRIES - retries + 1);

        service.handleFailure(task,
            "Error: " + e.getMessage(),
            e.getClass().getName(),
            retries - 1,
            backoff);
    } else {
        // Different strategies per worker:

        // Option 1: Create BPMN error (for critical workers)
        service.handleBpmnError(task, "CRITICAL_ERROR", e.getMessage());

        // Option 2: Complete with error flag (for non-critical workers)
        Map<String, Object> vars = Map.of(
            "taskFailed", true,
            "errorMessage", e.getMessage()
        );
        service.complete(task, vars);
    }
}
```

### 6.2 Fallback Behaviors by Worker

| Worker                   | Failure Strategy                 | Rationale                      |
| ------------------------ | -------------------------------- | ------------------------------ |
| RiskScoringWorker        | Complete with default score (50) | Don't block workflow           |
| ComplianceCheckWorker    | BPMN error → incident            | Critical for compliance        |
| EscalationWorker         | Complete with error flag         | Escalation is informational    |
| WorkflowCompletionWorker | BPMN error → incident            | Critical for data integrity    |
| NotificationWorker       | Complete with failure flag       | Notifications are non-blocking |

### 6.3 Global Exception Handler

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

## 7. Key Design Decisions

### 7.1 External Workers Only (No Java Delegates)

**Decision:** All service tasks use external task workers, not Java delegates.

**Rationale:**

- Workers can be deployed, scaled, and updated independently
- Failures don't block the workflow engine
- Built-in retry and incident management
- Workers can be written in any language
- Better separation of concerns

### 7.2 Non-Canceling Timer Boundary Events

**Decision:** `cancelActivity="false"` for all SLA timers.

**Rationale:**

- Original assignee can still complete the task after SLA breach
- Escalation is a notification, not a reassignment
- Both paths can complete without conflict

### 7.3 Hybrid Audit Model

**Decision:** Task-level events always captured; field-level changes selectively captured.

**Rationale:**

- Task events provide workflow visibility
- Field changes track decision rationale
- Balances completeness with storage efficiency

### 7.4 JWT for Camunda Identity

**Decision:** Use JWT authentication with runtime Camunda group sync.

**Rationale:**

- Centralizes authentication in Spring Security
- Avoids duplicate identity storage
- Groups synced at runtime for task queries

### 7.5 Camunda Webapp Authentication Integration

**Decision:** Custom `CamundaAuthenticationProvider` integrates Spring Security with Camunda's webapp.

**Implementation:**

```java
public class CamundaAuthenticationProvider extends ContainerBasedAuthenticationProvider {
    @Override
    public AuthenticationResult extractAuthenticatedUser(HttpServletRequest request, ProcessEngine engine) {
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            // Allow anonymous access for development - return admin user
            return new AuthenticationResult("admin", true);
        }
        return new AuthenticationResult(principal.getName(), true);
    }
}
```

**Rationale:**

- Allows Camunda Cockpit/Tasklist to work without separate login
- Uses Spring Security's authenticated principal when available
- Falls back to admin user for development convenience

### 7.6 H2 In-Memory Database (Development)

**Decision:** H2 in-memory database for development with easy switch to PostgreSQL for production.

**Rationale:**

- Zero setup required for development
- Camunda native support with LEGACY mode
- ACID compliance for regulatory requirements
- Easy to swap to PostgreSQL for production environments

---

## Summary of Components

| Component   | Count | Files                                                  |
| ----------- | ----- | ------------------------------------------------------ |
| Controllers | 5     | Auth, Workflow, Task, Audit, Health                    |
| Services    | 3     | Workflow, WorkflowTask, Audit                          |
| Workers     | 5     | Risk, Compliance, Escalation, Completion, Notification |
| Listeners   | 3     | TaskAudit, WorkflowStart, WorkflowEnd                  |
| Entities    | 2     | RegulatoryRequest, WorkflowAudit                       |
| DTOs        | 8     | Request/Response objects                               |
| Security    | 8     | JWT components + CamundaAuthenticationProvider         |
| Config      | 5     | Security, Camunda, CamundaSecurity, OpenAPI, Async     |

**Total Java Files:** 51 (excluding tests)

---

## Quick Reference

### Start a Workflow

```bash
curl -X POST http://localhost:8080/api/v1/workflow/start \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"requestTitle": "Test", "requestType": "FINANCIAL_PRODUCT"}'
```

### Complete a Task

```bash
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"decision": "APPROVED", "comment": "Looks good"}'
```

### External Task Topic Subscriptions

```txt
risk-scoring         → RiskScoringWorker
compliance-check     → ComplianceCheckWorker
escalation-handler   → EscalationWorker
workflow-completion  → WorkflowCompletionWorker
notification-service → NotificationWorker
```

---

## 8. Camunda Tasklist Forms

The system includes embedded forms for the Camunda Tasklist, allowing users to complete tasks directly from the Camunda webapp.

### Form Files

Located in `src/main/resources/static/forms/`:

| Form File                    | User Task                      | Candidate Group       |
| ---------------------------- | ------------------------------ | --------------------- |
| `initial-review.form`        | Initial Review                 | REVIEWER              |
| `manager-approval.form`      | Manager Approval               | MANAGER               |
| `senior-manager-review.form` | Senior Manager Review          | SENIOR_MANAGER        |
| `compliance-review.form`     | Compliance Manual Review       | COMPLIANCE            |
| `final-approval.form`        | Final Approval                 | ADMIN, SENIOR_MANAGER |
| `additional-info.form`       | Provide Additional Information | Submitter             |

### Form Structure

Each form uses Camunda's embedded form syntax with `cam-variable-name` attributes:

```html
<form role="form" class="form-horizontal">
  <!-- Read-only fields for context -->
  <div class="form-group">
    <label class="control-label col-md-3">Request Title</label>
    <div class="col-md-9">
      <input
        type="text"
        class="form-control"
        cam-variable-name="requestTitle"
        cam-variable-type="String"
        readonly
      />
    </div>
  </div>

  <!-- Decision dropdown -->
  <div class="form-group">
    <label class="control-label col-md-3">Decision *</label>
    <div class="col-md-9">
      <select
        class="form-control"
        cam-variable-name="reviewerDecision"
        cam-variable-type="String"
        required
      >
        <option value="">-- Select Decision --</option>
        <option value="APPROVED">Approve</option>
        <option value="REJECTED">Reject</option>
      </select>
    </div>
  </div>

  <!-- Comments -->
  <div class="form-group">
    <label class="control-label col-md-3">Comments</label>
    <div class="col-md-9">
      <textarea
        class="form-control"
        cam-variable-name="reviewerComment"
        cam-variable-type="String"
        rows="3"
      ></textarea>
    </div>
  </div>
</form>
```

### Using Camunda Tasklist

1. Navigate to <http://localhost:8080/camunda>
2. Login with `admin` / `admin`
3. Click **Tasklist**
4. View tasks assigned to your groups
5. Click a task to see the embedded form
6. Make a decision and click **Complete**

---

## 9. Database Configuration

### H2 In-Memory Database

The application uses H2 in-memory database for development simplicity:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:regulatory_db;DB_CLOSE_DELAY=-1;MODE=LEGACY;NON_KEYWORDS=VALUE
    username: sa
    password:
    driver-class-name: org.h2.Driver
  h2:
    console:
      enabled: true
      path: /h2-console
```

**Key Configuration:**

- `DB_CLOSE_DELAY=-1`: Keeps database alive while JVM runs
- `MODE=LEGACY`: Camunda compatibility mode
- `NON_KEYWORDS=VALUE`: Prevents SQL keyword conflicts

### Accessing H2 Console

1. Navigate to <http://localhost:8080/h2-console>
2. JDBC URL: `jdbc:h2:mem:regulatory_db`
3. Username: `sa`
4. Password: (empty)

---

## 10. Version Compatibility

| Component    | Version | Notes                                                |
| ------------ | ------- | ---------------------------------------------------- |
| Spring Boot  | 3.5.9   | Latest stable                                        |
| Camunda BPM  | 7.22.0  | Compatible with Spring Boot 3.5.x                    |
| H2 Database  | 2.3.x   | Via Spring Boot dependency management                |
| JAXB API     | 2.3.1   | Required for Camunda External Task Client on Java 21 |
| JAXB Runtime | 2.3.9   | Runtime implementation for JAXB                      |

### JAXB Dependencies (Java 21)

Camunda External Task Client requires JAXB which is not included in Java 21+:

```xml
<dependency>
    <groupId>javax.xml.bind</groupId>
    <artifactId>jaxb-api</artifactId>
    <version>2.3.1</version>
</dependency>
<dependency>
    <groupId>org.glassfish.jaxb</groupId>
    <artifactId>jaxb-runtime</artifactId>
    <version>2.3.9</version>
    <scope>runtime</scope>
</dependency>
```

---

## 11. Testing Guide

### Quick Start

```bash
# Build and run
mvn clean install
mvn spring-boot:run

# Verify application health
curl http://localhost:8080/api/v1/health
```

### Generate JWT Tokens

```bash
# REVIEWER token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "reviewer1", "roles": ["REVIEWER"], "department": "RISK"}'

# MANAGER token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "manager1", "roles": ["MANAGER"], "department": "OPERATIONS"}'

# ADMIN token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "admin1", "roles": ["ADMIN"], "department": "ADMINISTRATION"}'
```

Save tokens as environment variables: `TOKEN_REVIEWER`, `TOKEN_MANAGER`, `TOKEN_ADMIN`

### Test Happy Path (Full Approval)

#### Step 1: Start Workflow

```bash
curl -X POST http://localhost:8080/api/v1/workflow/start \
  -H "Authorization: Bearer $TOKEN_REVIEWER" \
  -H "Content-Type: application/json" \
  -d '{"requestTitle": "New Product", "requestType": "FINANCIAL_PRODUCT", "department": "INVESTMENT", "priority": "HIGH"}'
```

#### Step 2: Complete Initial Review

```bash
# Get tasks
curl -H "Authorization: Bearer $TOKEN_REVIEWER" http://localhost:8080/api/v1/tasks

# Claim and complete
curl -X POST -H "Authorization: Bearer $TOKEN_REVIEWER" http://localhost:8080/api/v1/tasks/{taskId}/claim
curl -X POST -H "Authorization: Bearer $TOKEN_REVIEWER" -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -d '{"decision": "APPROVED", "comment": "Verified"}'
```

#### Step 3: Complete Manager Approval

```bash
curl -H "Authorization: Bearer $TOKEN_MANAGER" http://localhost:8080/api/v1/tasks
curl -X POST -H "Authorization: Bearer $TOKEN_MANAGER" http://localhost:8080/api/v1/tasks/{taskId}/claim
curl -X POST -H "Authorization: Bearer $TOKEN_MANAGER" -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -d '{"decision": "APPROVED", "comment": "Business case validated"}'
```

#### Step 4: Complete Final Approval

```bash
curl -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/api/v1/tasks
curl -X POST -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/api/v1/tasks/{taskId}/claim
curl -X POST -H "Authorization: Bearer $TOKEN_ADMIN" -H "Content-Type: application/json" \
  http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -d '{"decision": "APPROVED", "comment": "Final approval granted"}'
```

#### Step 5: Verify Completion

```bash
curl -H "Authorization: Bearer $TOKEN_ADMIN" http://localhost:8080/api/v1/workflow/status/{processInstanceId}
# Expected: status: "APPROVED", currentStage: "COMPLETED"
```

### Test Alternate Flows

| Flow            | Action                                | Result                                |
| --------------- | ------------------------------------- | ------------------------------------- |
| Rejection       | `"decision": "REJECTED"` at any stage | Workflow ends with REJECTED status    |
| Additional Info | `"decision": "NEEDS_INFO"`            | Creates info request task, loops back |
| Escalation      | `"decision": "ESCALATE"` (Manager)    | Creates Senior Manager Review task    |

### API Endpoints Summary

| Endpoint                           | Method | Description          |
| ---------------------------------- | ------ | -------------------- |
| `/api/v1/auth/token`               | POST   | Generate JWT token   |
| `/api/v1/workflow/start`           | POST   | Start new workflow   |
| `/api/v1/workflow/{id}`            | GET    | Get workflow details |
| `/api/v1/workflow/status/{procId}` | GET    | Get workflow status  |
| `/api/v1/tasks`                    | GET    | Get available tasks  |
| `/api/v1/tasks/{id}/claim`         | POST   | Claim a task         |
| `/api/v1/tasks/{id}/complete`      | POST   | Complete a task      |
| `/api/v1/audit/process/{procId}`   | GET    | Get audit trail      |

### User Roles

| Role           | Permissions                                      |
| -------------- | ------------------------------------------------ |
| REVIEWER       | Start workflows, Initial Review tasks            |
| MANAGER        | Manager Approval tasks                           |
| SENIOR_MANAGER | Escalated reviews, Final Approval                |
| COMPLIANCE     | Compliance Manual Review tasks                   |
| ADMIN          | Full access, Final Approval, Terminate workflows |
| AUDITOR        | View audit trails only                           |

### Web UIs

- **Swagger UI**: <http://localhost:8080/swagger-ui.html>
- **Camunda Tasklist**: <http://localhost:8080/camunda/app/tasklist> (admin/admin)
- **Camunda Cockpit**: <http://localhost:8080/camunda/app/cockpit> (admin/admin)
- **H2 Console**: <http://localhost:8080/h2-console> (sa/empty)
