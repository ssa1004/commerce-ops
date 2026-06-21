# inventory-service — multi-stage build.
#
# Build context is the REPO ROOT (kept consistent with order-service, whose composite
# build needs modules/ in context). docker-compose.app.yml sets `context: ..`.
#
# Standalone build from repo root:
#   docker build -f services/inventory-service/Dockerfile -t commerce-ops/inventory-service .

FROM eclipse-temurin:25-jdk AS build
# pipefail: RUN 안의 파이프(find | head) 한 단계라도 실패하면 빌드를 멈춘다.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
WORKDIR /workspace
COPY services/inventory-service services/inventory-service
WORKDIR /workspace/services/inventory-service
# bootJar also emits a *-plain.jar; keep only the executable boot jar as app.jar.
RUN ./gradlew --no-daemon clean bootJar -x test \
 && app_jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -1)" \
 && cp "$app_jar" /workspace/app.jar

FROM eclipse-temurin:25-jre AS runtime

# OCI image labels — registry/Scanner 가 출처와 버전을 추적할 수 있게.
LABEL org.opencontainers.image.title="inventory-service" \
      org.opencontainers.image.description="commerce-ops inventory-service (Redis distributed-lock stock reservation)" \
      org.opencontainers.image.source="https://github.com/ssa1004/commerce-ops" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="ssa1004"

WORKDIR /app

# 런타임 베이스 보안 패치 + curl(HEALTHCHECK 용). 같은 RUN 에서 캐시 정리.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && apt-get install -y --no-install-recommends curl \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8083
ENV JAVA_OPTS=""

HEALTHCHECK --interval=30s --timeout=3s --start-period=150s --retries=3 \
  CMD curl -fsS http://localhost:8083/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
