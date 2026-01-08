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
    CMD wget -q --spider http://localhost:8080/api/v1/health || exit 1

# JVM options optimized for low memory (512MB free tier)
# - Xmx384m: Hard limit heap to 384MB
# - Xms256m: Start with 256MB heap
# - UseSerialGC: Lower memory overhead than G1GC
# - TieredStopAtLevel=1: Reduce JIT compilation memory
ENTRYPOINT ["java", \
    "-Xms256m", \
    "-Xmx384m", \
    "-XX:+UseSerialGC", \
    "-XX:TieredStopAtLevel=1", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
