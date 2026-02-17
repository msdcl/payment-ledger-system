# Multi-stage build for Java Spring Boot application
FROM gradle:8-jdk20 AS builder

WORKDIR /app

# Copy Gradle files
COPY build.gradle settings.gradle ./
COPY gradle ./gradle

# Copy source code
COPY src ./src

# Build the application
RUN gradle clean build -x test --no-daemon

# Production stage
FROM eclipse-temurin:20-jre-alpine

WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S springboot && \
    adduser -S springboot -u 1001

# Copy JAR from builder
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership
RUN chown springboot:springboot app.jar

USER springboot

# Expose port
EXPOSE 8050

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8050/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
