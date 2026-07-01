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

FROM eclipse-temurin:25-jdk AS build
# pipefail: RUN 안의 파이프(find | head) 한 단계라도 실패하면 빌드를 멈춘다.
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
WORKDIR /workspace

# Copy the composite-build modules first (changes here are rarer than service code).
COPY modules/slow-query-detector   modules/slow-query-detector
COPY modules/jfr-recorder-starter  modules/jfr-recorder-starter

# Copy the service and build a runnable boot jar (skip tests — CI is the test gate;
# the image build stays fast and needs no Postgres/Kafka/Redis).
COPY services/order-service services/order-service
WORKDIR /workspace/services/order-service
# bootJar also emits a *-plain.jar; keep only the executable boot jar as app.jar.
# A boot jar contains BOOT-INF/ so it's far larger than the thin -plain.jar — pick by size.
RUN ./gradlew --no-daemon clean bootJar -x test \
 && app_jar="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' | head -1)" \
 && cp "$app_jar" /workspace/app.jar

FROM eclipse-temurin:25-jre AS runtime

# OCI image labels — registry/Scanner 가 출처와 버전을 추적할 수 있게.
LABEL org.opencontainers.image.title="order-service" \
      org.opencontainers.image.description="commerce-ops order-service (SAGA orchestration + Outbox)" \
      org.opencontainers.image.source="https://github.com/ssa1004/commerce-ops" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="ssa1004"

WORKDIR /app

# 런타임 베이스의 보안 패치 적용 + curl(HEALTHCHECK 용) 설치. 같은 RUN 에서 캐시 정리해
# 레이어를 작게. apt 핀 경고(DL3008) 회피: curl 은 베이스 배포판 버전을 그대로 받는다.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get upgrade -y --no-install-recommends \
 && apt-get install -y --no-install-recommends curl \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

# Drop root.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8081
ENV JAVA_OPTS=""

# Spring Boot actuator liveness 로 컨테이너 헬스 판정. start-period 는 startupProbe 와
# 동일한 여유(150s)를 준다.
HEALTHCHECK --interval=30s --timeout=3s --start-period=150s --retries=3 \
  CMD curl -fsS http://localhost:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
