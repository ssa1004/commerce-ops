# inventory-service — multi-stage build.
#
# Build context is the REPO ROOT (kept consistent with order-service, whose composite
# build needs modules/ in context). docker-compose.app.yml sets `context: ..`.
#
# Standalone build from repo root:
#   docker build -f services/inventory-service/Dockerfile -t commerce-ops/inventory-service .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY services/inventory-service services/inventory-service
WORKDIR /workspace/services/inventory-service
# bootJar also emits a *-plain.jar; keep only the executable boot jar as app.jar.
RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v -- '-plain.jar' | head -1)" /workspace/app.jar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8083
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
