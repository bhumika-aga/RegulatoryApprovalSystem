# Multi-stage build for Regulatory Approval System
# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder

# Install Maven
RUN apk add --no-cache maven

WORKDIR /app

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src src

# Build the application
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup

# Copy the built artifact
COPY --from=builder /app/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose the application port
EXPOSE 8080

# Health check for container orchestration
HEALTHCHECK --interval=30s --timeout=10s --start-period=120s --retries=5 \
    CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

# JVM options for a 512 MB container (Render's free tier). Measured under load, the live heap is
# ~95 MB and class metadata ~125 MB (Camunda, Spring, Hibernate), so the heap is kept lean:
# - Xms64m / Xmx256m: start small and grow only as needed, capped well inside the container
# - Min/MaxHeapFreeRatio: keep little free heap committed, and give it back after a burst
# - Xss512k: half the default thread stack (Tomcat is also capped at 50 threads)
# - ReservedCodeCacheSize=64m: room for compiled code, fewer code-cache-triggered full GCs
# - UseSerialGC: lower memory overhead than G1GC
# - TieredStopAtLevel=1: Reduce JIT compilation memory
ENTRYPOINT ["java", \
    "-Xms64m", \
    "-Xmx256m", \
    "-XX:MinHeapFreeRatio=10", \
    "-XX:MaxHeapFreeRatio=30", \
    "-Xss512k", \
    "-XX:ReservedCodeCacheSize=64m", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
