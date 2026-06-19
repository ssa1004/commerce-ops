# order-service — multi-stage build.
#
# IMPORTANT: build context is the REPO ROOT, not services/order-service.
# order-service's settings.gradle.kts uses includeBuild("../../modules/...") for the
# slow-query-detector and jfr-recorder-starter composite builds, so those module
# sources must be present in the build context. The docker-compose.app.yml override
# already sets `context: ..` (repo root) + `dockerfile: services/order-service/Dockerfile`.
#
# Standalone build from repo root:
#   docker build -f services/order-service/Dockerfile -t commerce-ops/order-service .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the composite-build modules first (changes here are rarer than service code).
COPY modules/slow-query-detector   modules/slow-query-detector
COPY modules/jfr-recorder-starter  modules/jfr-recorder-starter

# Copy the service and build a runnable boot jar (skip tests — CI is the test gate;
# the image build stays fast and needs no Postgres/Kafka/Redis).
COPY services/order-service services/order-service
WORKDIR /workspace/services/order-service
# bootJar also emits a *-plain.jar; keep only the executable boot jar as app.jar.
RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Drop root.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8081
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
