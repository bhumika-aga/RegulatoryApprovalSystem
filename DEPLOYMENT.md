# Deployment Guide - Regulatory Approval System

This document provides a comprehensive guide for deploying the Regulatory Approval System, explaining the concepts, architecture decisions, and step-by-step deployment instructions for various platforms.

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Key Concepts](#2-key-concepts)
3. [Environment Configuration](#3-environment-configuration)
4. [Local Development](#4-local-development)
5. [Docker Deployment](#5-docker-deployment)
6. [Render Deployment](#6-render-deployment)
7. [Security Considerations](#7-security-considerations)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. Architecture Overview

### Application Components

The Regulatory Approval System is a monolithic Spring Boot application that includes:

```txt
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                       │
├─────────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ REST API     │  │ Camunda BPM  │  │ External     │          │
│  │ Controllers  │  │ Engine       │  │ Task Workers │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                           │                                      │
│                           ▼                                      │
│              ┌──────────────────────┐                           │
│              │   H2 In-Memory DB    │                           │
│              └──────────────────────┘                           │
└─────────────────────────────────────────────────────────────────┘
```

### External Task Worker Pattern

This application uses **External Task Workers** exclusively (no Java Delegates). This is a key architectural decision:

**Why External Task Workers?**

| Aspect     | Java Delegates            | External Task Workers    |
| ---------- | ------------------------- | ------------------------ |
| Coupling   | Tightly coupled to engine | Fully decoupled          |
| Scaling    | Scale with engine         | Scale independently      |
| Failure    | Blocks workflow           | Automatic retry/incident |
| Deployment | Redeploy engine           | Deploy independently     |
| Language   | Java only                 | Any language             |

**How It Works:**

1. BPMN defines service tasks with `camunda:type="external"` and a topic name
2. Workers poll the engine for tasks on their topic
3. Workers lock, execute, and complete tasks independently
4. Built-in retry mechanism handles transient failures

### Worker Topics

| Topic                  | Worker                   | Purpose                              |
| ---------------------- | ------------------------ | ------------------------------------ |
| `risk-scoring`         | RiskScoringWorker        | Calculate risk score for requests    |
| `compliance-check`     | ComplianceCheckWorker    | Validate regulatory compliance       |
| `escalation-handler`   | EscalationWorker         | Handle SLA breach escalations        |
| `workflow-completion`  | WorkflowCompletionWorker | Finalize workflow approval/rejection |
| `notification-service` | NotificationWorker       | Send notifications                   |

---

## 2. Key Concepts

### JWT Authentication

The system uses stateless JWT (JSON Web Token) authentication:

```txt
┌────────┐     1. Request Token      ┌────────────┐
│ Client │ ─────────────────────────► │ Auth API   │
│        │ ◄───────────────────────── │            │
└────────┘     2. JWT Token           └────────────┘
     │
     │ 3. Request with Bearer Token
     ▼
┌────────────┐     4. Validate JWT    ┌────────────┐
│ Protected  │ ─────────────────────► │ JWT Filter │
│ Endpoint   │                        │            │
└────────────┘                        └────────────┘
```

**Token Structure:**

```json
{
  "sub": "username",
  "roles": ["REVIEWER", "MANAGER"],
  "department": "RISK",
  "iss": "regulatory-approval-system",
  "exp": 1704153600
}
```

### Role-Based Access Control

| Role           | Permissions                                 |
| -------------- | ------------------------------------------- |
| REVIEWER       | Start workflows, Initial Review tasks       |
| MANAGER        | Manager Approval tasks, view team workflows |
| SENIOR_MANAGER | Handle escalations, Final Approval          |
| COMPLIANCE     | Compliance Review tasks                     |
| AUDITOR        | View audit trails only                      |
| ADMIN          | Full access, terminate workflows            |

### Camunda Integration

The application integrates Camunda BPM for workflow orchestration:

- **Process Runtime**: Executes BPMN workflows
- **Task Service**: Manages user tasks
- **External Task Client**: Polls for and executes external tasks
- **History Service**: Maintains workflow audit trail

---

## 3. Environment Configuration

### Required Environment Variables

| Variable                 | Description                                | How to Generate           |
| ------------------------ | ------------------------------------------ | ------------------------- |
| `JWT_SECRET`             | Base64-encoded 512-bit key for JWT signing | `openssl rand -base64 64` |
| `CAMUNDA_ADMIN_PASSWORD` | Password for Camunda admin user            | Choose a secure password  |

### Optional Environment Variables

| Variable                   | Description                                  | Default                                       |
| -------------------------- | -------------------------------------------- | --------------------------------------------- |
| `APP_CORS_ALLOWED_ORIGINS` | Comma-separated list of allowed CORS origins | `http://localhost:3000,http://localhost:8080` |
| `PORT`                     | Server port                                  | `8080`                                        |

### Security Best Practices

1. **Never commit secrets to version control**
2. **Use environment variables or secret managers**
3. **Rotate JWT secrets periodically**
4. **Use strong passwords for Camunda admin**

---

## 4. Local Development

### Prerequisites

- Java 17+
- Maven 3.9+

### Setup

```bash
# 1. Clone the repository
git clone <repository-url>
cd RegulatoryApprovalSystem

# 2. Set environment variables
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=admin

# 3. Build
mvn clean install

# 4. Run
mvn spring-boot:run
```

### Access Points

| Service        | URL                                     |
| -------------- | --------------------------------------- |
| Application    | <http://localhost:8080>                 |
| Swagger UI     | <http://localhost:8080/swagger-ui.html> |
| Camunda Webapp | <http://localhost:8080/camunda>         |
| H2 Console     | <http://localhost:8080/h2-console>      |
| Health Check   | <http://localhost:8080/api/v1/health>   |

### Testing the Workflow

```bash
# 1. Generate a token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "roles": ["ADMIN"], "department": "IT"}' \
  | jq -r '.data.token')

# 2. Start a workflow
curl -X POST http://localhost:8080/api/v1/workflow/start \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "requestTitle": "Test Request",
    "requestType": "FINANCIAL_PRODUCT",
    "department": "INVESTMENT"
  }'
```

---

## 5. Docker Deployment

### Dockerfile Explanation

The Dockerfile uses a multi-stage build for optimization:

```dockerfile
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
# - Uses Alpine for smaller image size
# - Includes JDK for compilation
# - Caches dependencies separately from source code

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
# - Uses JRE only (smaller footprint)
# - Non-root user for security
# - Health check for container orchestration
# - Optimized JVM flags for containers
```

**JVM Optimization Flags:**

| Flag                                      | Purpose                                 |
| ----------------------------------------- | --------------------------------------- |
| `-XX:+UseContainerSupport`                | Respect container memory limits         |
| `-XX:MaxRAMPercentage=75.0`               | Use up to 75% of available RAM for heap |
| `-XX:+UseG1GC`                            | G1 garbage collector for better latency |
| `-Djava.security.egd=file:/dev/./urandom` | Faster startup (non-blocking entropy)   |

### Running with Docker Compose

```bash
# 1. Set environment variables
export JWT_SECRET=$(openssl rand -base64 64)
export CAMUNDA_ADMIN_PASSWORD=your-secure-password

# 2. Build and run
docker-compose up -d

# 3. Check logs
docker-compose logs -f

# 4. Stop
docker-compose down
```

---

## 6. Render Deployment

### Overview

Render is a cloud platform that supports Docker deployments with automatic builds from Git repositories.

### Step-by-Step Deployment

#### Option 1: Manual Setup

1. **Create Account**: Sign up at [render.com](https://render.com)

2. **Create New Web Service**:
   - Click **New** → **Web Service**
   - Connect your GitHub/GitLab repository

3. **Configure Service**:

   | Setting         | Value                        |
   | --------------- | ---------------------------- |
   | Name            | `regulatory-approval-system` |
   | Region          | Choose nearest to your users |
   | Branch          | `main`                       |
   | Runtime         | `Docker`                     |
   | Dockerfile Path | `./Dockerfile`               |
   | Instance Type   | Starter ($7/month) or higher |

4. **Set Environment Variables**:
   - Click **Environment** → **Add Environment Variable**
   - Add `JWT_SECRET`: Generate with `openssl rand -base64 64`
   - Add `CAMUNDA_ADMIN_PASSWORD`: Your secure password

5. **Configure Health Check**:
   - Health Check Path: `/api/v1/health`

6. **Deploy**: Click **Create Web Service**

#### Option 2: Using Blueprint (render.yaml)

1. **Push render.yaml** to your repository (already included)

2. **Create Blueprint**:
   - Click **New** → **Blueprint**
   - Connect your repository
   - Render auto-detects `render.yaml`

3. **Set Secret Variables** when prompted

### render.yaml Explanation

```yaml
services:
  - type: web # Web service type
    name: regulatory-approval-system
    runtime: docker # Use Docker runtime
    dockerfilePath: ./Dockerfile
    plan: starter # Instance type
    healthCheckPath: /api/v1/health # Health endpoint
    envVars:
      - key: JWT_SECRET
        sync: false # Must be set manually (secret)
      - key: CAMUNDA_ADMIN_PASSWORD
        sync: false # Must be set manually (secret)
```

### Post-Deployment Verification

```bash
# Check health
curl https://regulatory-approval-system.onrender.com/api/v1/health

# Generate token
curl -X POST https://regulatory-approval-system.onrender.com/api/v1/auth/token \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "roles": ["ADMIN"], "department": "IT"}'
```

---

## 7. Security Considerations

### Production Checklist

- [ ] **JWT Secret**: Use a cryptographically secure random key (512+ bits)
- [ ] **Camunda Password**: Use a strong, unique password
- [ ] **HTTPS**: Ensure all traffic is encrypted (Render provides this by default)
- [ ] **CORS**: Configure allowed origins for your frontend domain
- [ ] **H2 Console**: Consider disabling in production (set `spring.h2.console.enabled=false`)

### Database Considerations

The application uses H2 in-memory database by default. This means:

- **Data is not persisted** across restarts
- **Suitable for demos and development**
- **For production**, consider:
  - PostgreSQL on Render
  - External managed database

### Switching to PostgreSQL (Future Enhancement)

```yaml
# application.yml changes for PostgreSQL
spring:
  datasource:
    url: ${DATABASE_URL}
    driver-class-name: org.postgresql.Driver
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
```

---

## 8. Troubleshooting

### Common Issues

#### Application Won't Start

**Symptom**: `JWT secret key must be configured` error

**Solution**: Ensure `JWT_SECRET` environment variable is set:

```bash
export JWT_SECRET=$(openssl rand -base64 64)
```

#### Health Check Failing

**Symptom**: Render shows unhealthy status

**Solution**:

1. Check logs for startup errors
2. Ensure health check path is `/api/v1/health`
3. Increase start period if app takes time to initialize

#### Memory Issues

**Symptom**: `OutOfMemoryError` or slow performance

**Solution**:

1. Upgrade to larger instance (min 512MB RAM recommended)
2. The JVM is configured to use 75% of available RAM

#### Camunda Webapp Not Loading

**Symptom**: 401 Unauthorized on Camunda webapp

**Solution**:

1. The Camunda webapp requires authentication
2. Generate a JWT token first and use it
3. Or access via Swagger UI endpoints

### Viewing Logs

**Render Dashboard**:

1. Go to your service
2. Click **Logs** tab
3. View real-time and historical logs

**Docker**:

```bash
docker-compose logs -f app
```

### Health Check Endpoints

| Endpoint           | Purpose                |
| ------------------ | ---------------------- |
| `/api/v1/health`   | Application health     |
| `/actuator/health` | Spring Actuator health |
| `/actuator/info`   | Application info       |

---

## Summary

This Regulatory Approval System is designed to be:

1. **Secure**: No hardcoded secrets, JWT authentication, role-based access
2. **Scalable**: External Task Worker pattern for decoupled execution
3. **Deployable**: Docker-ready with Render blueprint included
4. **Observable**: Health checks, logging, and audit trail

For questions or issues, refer to the other documentation files:

- [README.md](README.md) - Quick start and API reference
- [IMPLEMENTATION.md](IMPLEMENTATION.md) - Implementation details
- [ARCHITECTURE.md](ARCHITECTURE.md) - System architecture
