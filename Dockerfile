FROM gradle:8.5-jdk17 AS build

WORKDIR /app

# Copy gradle files first for better layer caching
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY gradlew ./

# Download dependencies
RUN ./gradlew build --no-daemon --stacktrace || true

# Copy source code
COPY src ./src

# Build the application
RUN ./gradlew shadowJar --no-daemon --stacktrace

FROM eclipse-temurin:17-jre-alpine

# Add curl for health check
RUN apk add --no-cache curl

WORKDIR /app

# Copy the fat jar
COPY --from=build /app/build/libs/*-all.jar app.jar

# Create data directory and set permissions
RUN mkdir -p /app/data && \
    chown -R 1000:1000 /app && \
    chmod +x /app/data

VOLUME /app/data

EXPOSE 8080

# Use non-root user for security
USER 1000:1000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

CMD ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
