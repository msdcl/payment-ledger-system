# Multi-stage build for Java Spring Boot application
FROM gradle:8-jdk20 AS builder

WORKDIR /app

# Copy Gradle build files first (for Docker layer caching)
# The gradle:8-jdk20 image already has Gradle installed, so we don't need the wrapper
COPY build.gradle settings.gradle ./

# Copy source code
COPY src ./src

# Build the application
RUN gradle clean build -x test --no-daemon

# Production stage
# Using jammy (Ubuntu) instead of alpine because alpine doesn't have ARM64
# builds for Java 20. On Apple Silicon (M1/M2/M3), you need ARM64 images.
# jammy is slightly larger (~250MB vs ~80MB) but supports all platforms.
FROM eclipse-temurin:20-jre-jammy

WORKDIR /app

# Create non-root user (Ubuntu/jammy syntax differs from Alpine)
RUN groupadd -g 1001 springboot && \
    useradd -u 1001 -g springboot -s /bin/false springboot

# Copy JAR from builder
COPY --from=builder /app/build/libs/*.jar app.jar

# Change ownership
RUN chown springboot:springboot app.jar

USER springboot

# Expose port
EXPOSE 8050

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD curl --fail --silent http://localhost:8050/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
