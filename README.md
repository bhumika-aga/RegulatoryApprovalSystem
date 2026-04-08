# Regulatory Approval System

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.13-green.svg)](https://spring.io/projects/spring-boot)
[![Camunda](https://img.shields.io/badge/Camunda-7.22.0-blue.svg)](https://camunda.com/)
[![H2](https://img.shields.io/badge/H2-In--Memory-blue.svg)](https://www.h2database.com/)

An enterprise-grade BPMN-based regulatory approval workflow system built with Spring Boot 3 and Camunda 7. This system implements complex regulatory approval workflows typical in BFSI (Banking, Financial Services, and Insurance) and healthcare domains.

## 🚀 Overview

The Regulatory Approval System orchestrates a multi-stage approval process using the **External Task Worker pattern** for maximum scalability and decoupling. Unlike traditional Java Delegate implementations, this system ensures that business logic is completely decoupled from the workflow engine, allowing for independent scaling and resilient failure handling.

### Core Workflow Flow

```txt
Submit → Initial Review → Manager Approval → Compliance Check → Final Approval → Complete
           (8h SLA)         (24h SLA)          (48h SLA)          (8h SLA)
               ↓                 ↓                  ↓                  ↓
           Escalate to      Escalate to        Manual Review      Escalate to
            Manager        Senior Manager      (if required)        Admin
```

---

## 🧠 Logical Implementation & Concepts

### 1. BPMN Workflow Orchestration

The heart of the system is the `regulatory-approval-process.bpmn`. It defines the sequence of operations, user tasks, service tasks, and gateways.

- **User Tasks**: Managed by Camunda's Task Service. Integrated with JWT roles for candidate group assignments.
- **Service Tasks**: Implemented as **External Tasks**. The engine publishes work to "topics," and independent workers poll and execute them.
- **Timers & SLAs**: Non-canceling boundary timer events enforce response times. If a task exceeds its SLA, a parallel escalation flow is triggered without cancelling the original task.

### 2. External Task Worker Pattern

We use 5 specialized external task workers:

- **`risk-scoring`**: Calculates risk level based on request attributes.
- **`compliance-check`**: Automated validation against regulatory rules.
- **`escalation-handler`**: Processes SLA breaches and updates audit trails.
- **`workflow-completion`**: Finalizes the request status (APPROVED/REJECTED).
- **`notification-service`**: Handles asynchronous user communications.

### 3. Resilience & Fallbacks

Each worker implements a robust error handling strategy:

- **Retries**: Automatic retries with exponential backoff (5s, 10s, 15s).
- **Fallbacks**:
  - _Risk Worker_: Falls back to a default score (50) to prevent blocking.
  - _Compliance Worker_: Raises a BPMN Error if critical validation fails, creating an incident for manual intervention.
  - _Notification Worker_: Silently logs failures to ensure notifications are non-blocking.

### 4. Audit Trail

Comprehensive auditing is captured via `TaskAuditListener` and workers:

- **Task Events**: Created, Claimed, Completed.
- **Workflow Events**: Started, Ended, Escalated.
- **Data Changes**: Field-level changes and decisions are recorded with timestamps and actor details.

---

## 🏗️ Architecture

### High-Level Components

- **REST API Layer**: Spring Boot Controllers handling Auth, Workflow, and Tasks.
- **Service Layer**: Business orchestration and JPA repository interactions.
- **Engine Layer**: Camunda 7 Embedded Process Engine.
- **Worker Layer**: Independent External Task Clients polling the engine.
- **Persistence Layer**: H2 In-Memory (Dev) with Flyway migrations.

---

## 🔐 Security & Roles

Authentication is handled via **JWT (JSON Web Tokens)**. Roles are synchronized between Spring Security and Camunda Identity Service at runtime.

| Role               | Permissions                               |
| :----------------- | :---------------------------------------- |
| **REVIEWER**       | Start workflows, Initial Assessment tasks |
| **MANAGER**        | Business approvals, Team visibility       |
| **SENIOR_MANAGER** | Escalation handling, Final Approvals      |
| **COMPLIANCE**     | Regulatory manual reviews                 |
| **AUDITOR**        | Read-only audit trail access              |
| **ADMIN**          | Full system access, Termination rights    |

---

## 🛠️ Technology Stack

- **Framework**: Spring Boot 3.5.13
- **Workflow**: Camunda BPM 7.22.0
- **Security**: Spring Security + JJWT
- **API Docs**: SpringDoc OpenAPI (Swagger)
- **Database**: H2 (In-Memory) / Flyway
- **Tooling**: Lombok, MapStruct

---

## 📂 Project Structure

```txt
src/main/java/com/enterprise/regulatory/
├── config/          # Spring & Camunda configuration
├── controller/      # REST API endpoints
├── service/         # Business logic orchestration
├── worker/          # External task workers
├── listener/        # Camunda event listeners
├── security/        # JWT & Identity mapping
├── model/           # Entities & enums
├── dto/             # Request/Response data objects
├── repository/      # Data access (JPA)
└── exception/       # Global error handling
```

---

## 🚦 Getting Started

### Prerequisites

- Java 17+
- Maven 3.9+

### Environment Variables

```bash
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=admin
```

### Running Locally

```bash
mvn clean install
mvn spring-boot:run
```

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Camunda Webapp**: `http://localhost:8080/camunda` (Login: `admin` / `${CAMUNDA_ADMIN_PASSWORD}`)
- **H2 Console**: `http://localhost:8080/h2-console`

---

## 🐳 Deployment & Docker

The project is container-optimized with a multi-stage `Dockerfile`.

### Docker Compose

```bash
docker-compose up -d
```

### Render Deployment

This project is pre-configured for **Render** via `render.yaml`. Use the Blueprint feature in the Render dashboard to deploy automatically.
