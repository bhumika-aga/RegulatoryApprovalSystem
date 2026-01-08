# Regulatory Approval System

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-green.svg)](https://spring.io/projects/spring-boot)
[![Camunda](https://img.shields.io/badge/Camunda-7.22.0-blue.svg)](https://camunda.com/)
[![H2](https://img.shields.io/badge/H2-In--Memory-blue.svg)](https://www.h2database.com/)

An enterprise-grade BPMN-based regulatory approval workflow system built with Spring Boot 3 and Camunda 7. This system implements regulatory approval workflows typical in BFSI (Banking, Financial Services, and Insurance) and healthcare domains using the **External Task Worker pattern** for maximum scalability and decoupling.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Deployment](#deployment)
- [API Reference](#api-reference)
- [Workflow Stages](#workflow-stages)
- [Security](#security)
- [Configuration](#configuration)
- [Documentation](#documentation)

## Overview

The Regulatory Approval System implements a multi-stage approval workflow with the following chain:

```txt
┌─────────┐    ┌──────────┐    ┌─────────┐    ┌────────────┐    ┌───────┐
│ Submit  │───►│ Reviewer │───►│ Manager │───►│ Compliance │───►│ Final │
│ Request │    │  (8h)    │    │  (24h)  │    │   (48h)    │    │ (8h)  │
└─────────┘    └────┬─────┘    └────┬────┘    └─────┬──────┘    └───┬───┘
                    │               │               │               │
                    ▼               ▼               ▼               ▼
               Escalate to    Escalate to     Escalate to     Escalate to
                 Manager      Senior Mgr     Compliance Lead     Admin
```

## Features

### Core Workflow Features

- **Multi-stage approval workflow**: Reviewer → Manager → Compliance → Final Approval
- **SLA enforcement**: Timer boundary events with configurable durations (8h/24h/48h)
- **Automatic escalation**: Tasks escalate to senior roles on SLA breach
- **Risk scoring**: Automated risk assessment using external task workers
- **Compliance checking**: Decoupled compliance validation via external workers

### Security Features

- **JWT authentication**: Stateless token-based authentication
- **Role-based authorization**: 6 distinct roles with granular permissions
- **Method-level security**: Spring Security `@PreAuthorize` annotations
- **Camunda identity integration**: JWT roles synced to Camunda groups at runtime
- **No hardcoded secrets**: All sensitive configuration via environment variables

### Audit Features

- **Complete audit trail**: All workflow events recorded to database
- **Decision tracking**: Approval/rejection decisions with comments
- **SLA breach logging**: Escalation events tracked with timestamps
- **User action history**: Full traceability of who did what and when

### Technical Features

- **External Task Workers**: Decoupled service execution for scalability (no Java Delegates)
- **Production-ready**: Proper error handling, health checks, container-optimized
- **API documentation**: OpenAPI 3.0 with Swagger UI
- **Docker support**: Multi-stage Dockerfile with health checks
- **Render-ready**: Blueprint configuration included

## Architecture

### High-Level Architecture

```txt
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Client Applications                                │
│                    (Web UI, Mobile Apps, API Consumers)                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                            REST API Layer                                    │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐           │
│  │   Auth      │ │  Workflow   │ │    Task     │ │   Audit     │           │
│  │ Controller  │ │ Controller  │ │ Controller  │ │ Controller  │           │
│  └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘           │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Service Layer                                      │
│  ┌─────────────┐ ┌─────────────────────┐ ┌─────────────┐                   │
│  │  Workflow   │ │  WorkflowTask       │ │   Audit     │                   │
│  │  Service    │ │  Service            │ │  Service    │                   │
│  └─────────────┘ └─────────────────────┘ └─────────────┘                   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    ▼                                   ▼
┌──────────────────────────────┐    ┌──────────────────────────────────────────┐
│      Camunda BPM Engine      │    │          External Task Workers           │
│  ┌────────────────────────┐  │    │  ┌──────────┐ ┌──────────┐ ┌──────────┐ │
│  │   Process Runtime      │  │    │  │Compliance│ │   Risk   │ │Notifica- │ │
│  │   Task Service         │  │    │  │  Worker  │ │  Worker  │ │  tion    │ │
│  │   History Service      │  │    │  └──────────┘ └──────────┘ └──────────┘ │
│  └────────────────────────┘  │    └──────────────────────────────────────────┘
│  ┌────────────────────────┐  │
│  │   Task Listeners       │  │
│  │   Execution Listeners  │  │
│  └────────────────────────┘  │
└──────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         H2 In-Memory Database                                │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐               │
│  │ Camunda Tables  │ │ Regulatory      │ │ Workflow Audit  │               │
│  │ (ACT_*)         │ │ Request Table   │ │ Table           │               │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘               │
└─────────────────────────────────────────────────────────────────────────────┘
```

### External Task Worker Pattern

All service tasks use External Task Workers (no Java Delegates) for maximum decoupling:

```txt
┌──────────────────┐     Poll for Tasks      ┌──────────────────┐
│  Camunda Engine  │ ◄─────────────────────── │  External Worker │
│                  │                          │                  │
│  External Task   │ ──────────────────────► │  Execute Logic   │
│  Topic: X        │     Fetch & Lock        │                  │
│                  │                          │  Complete Task   │
│                  │ ◄─────────────────────── │  with Variables  │
└──────────────────┘                          └──────────────────┘
```

**Benefits:**

- Decouples workflow from business logic
- Enables polyglot workers (any language)
- Production-friendly horizontal scaling
- Resilient failure handling with retries
- Independent deployment and scaling

## Technology Stack

| Component       | Technology            | Version  |
| --------------- | --------------------- | -------- |
| Runtime         | Java                  | 21 (LTS) |
| Framework       | Spring Boot           | 3.5.9    |
| Workflow Engine | Camunda BPM           | 7.22.0   |
| Database        | H2 (In-Memory)        | 2.3.x    |
| Security        | Spring Security + JWT | 6.x      |
| API Docs        | SpringDoc OpenAPI     | 2.8.0    |
| Build           | Maven                 | 3.9+     |

## Project Structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/                    # Configuration classes
│   ├── SecurityConfig.java    # Spring Security configuration
│   ├── CamundaConfig.java     # Camunda engine configuration
│   ├── CamundaSecurityConfig.java # Camunda webapp authentication
│   ├── OpenApiConfig.java     # Swagger/OpenAPI configuration
│   └── AsyncConfig.java       # Async execution configuration
├── controller/                # REST API controllers
│   ├── AuthController.java    # Authentication endpoints
│   ├── WorkflowController.java # Workflow operations
│   ├── TaskController.java    # Task management
│   ├── AuditController.java   # Audit trail access
│   └── HealthController.java  # Health check
├── service/                   # Business logic services
│   ├── WorkflowService.java   # Workflow operations
│   ├── WorkflowTaskService.java # Task operations
│   └── AuditService.java      # Audit operations
├── listener/                  # Camunda Listeners
│   ├── TaskAuditListener.java
│   ├── WorkflowStartListener.java
│   └── WorkflowEndListener.java
├── worker/                    # External Task Workers
│   ├── ExternalTaskWorkerConfig.java
│   ├── ComplianceCheckWorker.java
│   ├── RiskScoringWorker.java
│   ├── EscalationWorker.java
│   ├── WorkflowCompletionWorker.java
│   └── NotificationWorker.java
├── security/                  # Security components
│   ├── JwtTokenProvider.java
│   ├── JwtAuthenticationFilter.java
│   ├── JwtAuthenticationEntryPoint.java
│   ├── JwtProperties.java
│   ├── UserPrincipal.java
│   ├── SecurityUtils.java
│   ├── CamundaIdentityService.java
│   └── CamundaAuthenticationProvider.java
├── model/                     # Domain models
│   ├── entity/
│   │   ├── RegulatoryRequest.java
│   │   └── WorkflowAudit.java
│   └── enums/
│       ├── ApprovalStatus.java
│       ├── AuditEventType.java
│       ├── ComplianceResult.java
│       └── UserRole.java
├── dto/                       # Data Transfer Objects
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
├── repository/                # Data access
│   ├── RegulatoryRequestRepository.java
│   └── WorkflowAuditRepository.java
├── exception/                 # Exception handling
│   ├── GlobalExceptionHandler.java
│   ├── ResourceNotFoundException.java
│   ├── WorkflowException.java
│   └── TaskOperationException.java
└── RegulatoryApprovalApplication.java

src/main/resources/
├── bpmn/
│   └── regulatory-approval-process.bpmn
├── static/forms/              # Camunda Tasklist forms
│   ├── initial-review.form
│   ├── manager-approval.form
│   ├── compliance-review.form
│   ├── final-approval.form
│   ├── senior-manager-review.form
│   └── additional-info.form
└── application.yml
```

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker (optional)

### Environment Variables (Required)

Before running, set these environment variables:

```bash
# Generate a secure JWT secret (Base64 encoded, min 512-bit)
export JWT_SECRET=$(openssl rand -base64 64)

# Set Camunda admin password
export CAMUNDA_ADMIN_PASSWORD=your-secure-password
```

### Quick Start (Local Development)

```bash
# Clone the repository
git clone <repository-url>
cd RegulatoryApprovalSystem

# Set required environment variables
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=admin

# Build and run
mvn clean install
mvn spring-boot:run

# Access the application
# Swagger UI: http://localhost:8080/swagger-ui.html
# Camunda Webapp: http://localhost:8080/camunda
# H2 Console: http://localhost:8080/h2-console
# Health Check: http://localhost:8080/api/v1/health
```

### Quick Start with Docker

```bash
# Set environment variables
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=your-secure-password

# Run with Docker Compose
docker-compose up -d
```

### Verify Installation

```bash
curl http://localhost:8080/api/v1/health
```

## Deployment

### Deploy to Render

1. **Fork/Push** this repository to your GitHub account

2. **Create a new Web Service** on [Render Dashboard](https://dashboard.render.com)

3. **Connect your repository** and select Docker as the runtime

4. **Set environment variables** in Render dashboard:

   - `JWT_SECRET`: Generate with `openssl rand -base64 64`
   - `CAMUNDA_ADMIN_PASSWORD`: Your secure admin password

5. **Configure settings**:

   - Health Check Path: `/api/v1/health`
   - Instance Type: Starter or higher (min 512MB RAM recommended)

6. **Deploy** - Render will build and deploy automatically

### Using Render Blueprint

This project includes a `render.yaml` blueprint file. You can use it for one-click deployment:

1. Click **New** → **Blueprint** in Render Dashboard
2. Connect your repository
3. Render will detect `render.yaml` and configure the service
4. Set the required environment variables when prompted

### Environment Variables for Production

| Variable                   | Required | Description                            |
| -------------------------- | -------- | -------------------------------------- |
| `JWT_SECRET`               | Yes      | JWT signing key (Base64, min 512-bit)  |
| `CAMUNDA_ADMIN_PASSWORD`   | Yes      | Camunda admin password                 |
| `APP_CORS_ALLOWED_ORIGINS` | No       | CORS allowed origins (comma-separated) |
| `PORT`                     | No       | Server port (default: 8080)            |

## API Reference

### Authentication

```bash
# Generate JWT token
curl -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "username": "reviewer1",
    "roles": ["REVIEWER"],
    "department": "RISK"
  }'
```

### Workflow Operations

```bash
# Start a new workflow
curl -X POST http://localhost:8080/api/v1/workflow/start \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "requestTitle": "New Product Approval",
    "requestDescription": "Request for new investment product",
    "requestType": "FINANCIAL_PRODUCT",
    "department": "INVESTMENT",
    "priority": "HIGH"
  }'

# Get workflow status
curl -X GET http://localhost:8080/api/v1/workflow/status/{processInstanceId} \
  -H "Authorization: Bearer <token>"

# Get my workflows
curl -X GET http://localhost:8080/api/v1/workflow/my-requests \
  -H "Authorization: Bearer <token>"
```

### Task Operations

```bash
# Get tasks for current user
curl -X GET http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer <token>"

# Claim a task
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/claim \
  -H "Authorization: Bearer <token>"

# Complete a task
curl -X POST http://localhost:8080/api/v1/tasks/{taskId}/complete \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "decision": "APPROVED",
    "comment": "Requirements verified, approved for next stage"
  }'
```

### Audit Operations

```bash
# Get audit trail for a process (requires AUDITOR, ADMIN, or COMPLIANCE role)
curl -X GET http://localhost:8080/api/v1/audit/process/{processInstanceId} \
  -H "Authorization: Bearer <token>"

# Get SLA breaches
curl -X GET "http://localhost:8080/api/v1/audit/sla-breaches?since=2024-01-01T00:00:00" \
  -H "Authorization: Bearer <token>"
```

## Workflow Stages

### 1. Initial Review (8h SLA)

- **Candidate Group**: REVIEWER
- **Decisions**: APPROVED, REJECTED, NEEDS_INFO
- **Escalation**: Manager on timeout

### 2. Manager Approval (24h SLA)

- **Candidate Group**: MANAGER
- **Decisions**: APPROVED, REJECTED, ESCALATE
- **Escalation**: Senior Manager on timeout

### 3. Senior Manager Review (for escalated cases)

- **Candidate Group**: SENIOR_MANAGER
- **Decisions**: APPROVED, REJECTED

### 4. Compliance Check (48h SLA) - External Task

- **Topic**: compliance-check
- **Results**: PASS, FAIL, REQUIRES_ADDITIONAL_INFO

### 5. Compliance Manual Review (if needed)

- **Candidate Group**: COMPLIANCE
- **Decisions**: PASS, FAIL

### 6. Final Approval (8h SLA)

- **Candidate Groups**: ADMIN, SENIOR_MANAGER
- **Decisions**: APPROVED, REJECTED

## Security

### Roles

| Role           | Description         | Permissions                                 |
| -------------- | ------------------- | ------------------------------------------- |
| REVIEWER       | Initial assessment  | Start workflow, Initial Review tasks        |
| MANAGER        | Business approval   | Manager Approval tasks, view team workflows |
| SENIOR_MANAGER | Escalation handling | Handle escalations, Final Approval          |
| COMPLIANCE     | Regulatory checks   | Compliance Review tasks                     |
| AUDITOR        | Read-only audit     | View audit trails only                      |
| ADMIN          | Full access         | All operations, terminate workflows         |

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

## Configuration

### Environment Variables

| Variable                   | Description                       | Required |
| -------------------------- | --------------------------------- | -------- |
| `JWT_SECRET`               | JWT signing key (Base64, 512-bit) | Yes      |
| `CAMUNDA_ADMIN_PASSWORD`   | Camunda admin password            | Yes      |
| `APP_CORS_ALLOWED_ORIGINS` | CORS allowed origins              | No       |

### Database Configuration

The application uses **H2 in-memory database** by default:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:regulatory_db;DB_CLOSE_DELAY=-1;MODE=LEGACY
    username: sa
    password: (empty)
```

Access the H2 console at: `http://localhost:8080/h2-console`

### SLA Configuration

SLA timers are configured in the BPMN file using ISO-8601 durations:

| Stage            | Duration | BPMN Timer | Escalation Target |
| ---------------- | -------- | ---------- | ----------------- |
| Initial Review   | 8 hours  | `PT8H`     | Manager           |
| Manager Approval | 24 hours | `PT24H`    | Senior Manager    |
| Compliance Check | 48 hours | `PT48H`    | Compliance Lead   |
| Final Approval   | 8 hours  | `PT8H`     | Admin             |

## Documentation

- [IMPLEMENTATION.md](IMPLEMENTATION.md) - Implementation guide, design decisions, and testing instructions
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture and design patterns
- [DEPLOYMENT.md](DEPLOYMENT.md) - Detailed deployment guide and concepts
- [Swagger UI](http://localhost:8080/swagger-ui.html) - Interactive API documentation
- [Camunda Webapp](http://localhost:8080/camunda) - Process monitoring and administration
- [H2 Console](http://localhost:8080/h2-console) - Database console (JDBC URL: jdbc:h2:mem:regulatory_db)
