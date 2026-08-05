# ============================================================
# Spring Boot — multi-stage Docker build (build → slim runtime)
# ============================================================

# ---- Stage 1: Build ----
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Copy Gradle config first — leverages Docker layer caching
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle/wrapper/ ./gradle/wrapper/
COPY mobile-common/build.gradle ./mobile-common/
COPY mobile-security/build.gradle ./mobile-security/
COPY mobile-data/build.gradle ./mobile-data/
COPY mobile-business/build.gradle ./mobile-business/
COPY mobile-api/build.gradle ./mobile-api/
RUN ./gradlew dependencies --no-daemon || true

# Copy source and build
COPY . .
RUN ./gradlew :mobile-api:bootJar --no-daemon -x test

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user (security best practice)
RUN addgroup -g 1000 appgroup && \
    adduser -u 1000 -G appgroup -D appuser
RUN mkdir -p /var/www/adproject-mobile/images && \
    chown -R appuser:appgroup /var/www/adproject-mobile

COPY --from=builder /app/mobile-api/build/libs/adproject-mobile-api.jar app.jar

USER appuser
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
