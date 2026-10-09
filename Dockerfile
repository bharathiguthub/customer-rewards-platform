# Multi-stage Dockerfile for Customer Rewards Spring Boot Application
# Build stage: Compile and build the application
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Copy gradle wrapper and configuration files - must come first
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY settings.gradle.kts build.gradle.kts ./

# Copy source code
COPY src src/

# Build the application using the Gradle wrapper
# Convert gradlew to Unix line endings and make it executable
RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew && \
    ./gradlew build -x test --no-daemon

# Runtime stage: Lightweight Java runtime with the built application
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Create non-root user for running the application
RUN groupadd -r appuser && useradd -r -g appuser appuser

# Copy the built JAR from the builder stage
# Use pattern to select only the bootJar (executable with embedded server)
# Excludes the -plain.jar which cannot run standalone
COPY --from=builder /app/build/libs/customer-rewards-*SNAPSHOT.jar app.jar

# Ensure the JAR is owned by the non-root user
RUN chown -R appuser:appuser /app

# Switch to non-root user
USER appuser

# Expose application and management ports
EXPOSE 8080 8081

# Set ENTRYPOINT to run the Spring Boot application
ENTRYPOINT ["java", "-jar", "app.jar"]
