#!/usr/bin/env bash
# integration-demo.sh — 8 레포 portfolio set 의 cross-repo 통합 시연.
#
# 본 스크립트는 다음 흐름을 한 번에 보여준다:
#   1) 통합 stack up (옵저버빌리티 + auth-stub + Jaeger)
#   2) 3 개 service 가 host 에서 떠있는지 확인 (안 떠있으면 안내만 하고 진행)
#   3) mock JWT (HS256) + traceparent header 로 POST /orders → trace_id 가 logs(MDC) /
#      Tempo / Jaeger 에 같은 ID 로 잡히는지 확인 흐름 출력
#   4) GET /orders 로 N+1 패턴 일부러 발생 — slow-query-detector 의 메트릭 증가 확인
#   5) POST /actuator/jfr/{tag} 로 JFR ad-hoc dump — jfr-recorder-starter 동작 확인
#   6) 결과를 한눈에 보는 Grafana / Loki / Tempo / Jaeger / Prometheus URL 출력
#
# 실행 위치: 레포 루트.
#   ./scripts/integration-demo.sh
#
# 의존: docker (compose v2), curl, openssl, base64, python3 (stdlib 만)
# 외부 의존성은 stack 안에서 자체 해결 — 인터넷 필요는 docker pull 한 번.

set -euo pipefail

# ---------- 0. 위치 / 의존성 ----------
SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
REPO_ROOT="$( cd -- "${SCRIPT_DIR}/.." &> /dev/null && pwd )"
cd "${REPO_ROOT}"

ORDER_URL="${ORDER_URL:-http://localhost:8081}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8082}"
INVENTORY_URL="${INVENTORY_URL:-http://localhost:8083}"
AUTH_STUB_URL="${AUTH_STUB_URL:-http://localhost:9000}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
JAEGER_URL="${JAEGER_URL:-http://localhost:16686}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
LOKI_URL="${LOKI_URL:-http://localhost:3100}"

for cmd in docker curl openssl base64 python3; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[error] '$cmd' 가 PATH 에 없음 — 설치 후 다시 실행" >&2
        exit 1
    fi
done

step() { printf "\n========== %s ==========\n" "$*"; }
note() { printf "  [note] %s\n" "$*"; }
ok()   { printf "  [ok]   %s\n" "$*"; }
warn() { printf "  [warn] %s\n" "$*"; }

# ---------- 1. Stack up ----------
step "1) 통합 stack up"

COMPOSE_FILES=(-f infra/docker-compose.yml -f infra/docker-compose.integration.yml)

if ! docker compose "${COMPOSE_FILES[@]}" config >/dev/null 2>&1; then
    echo "[error] compose config 파싱 실패 — yml 문법 확인" >&2
    exit 1
fi

# 통합 데모에 *직접 필요한* 컨테이너만 명시 — kafka 는 별도 흐름 (Outbox publish) 으로 본 데모
# scope 밖. service 컨테이너 (order/payment/inventory) 도 host 의 ./gradlew bootRun 으로
# 띄우는 것이 본 레포의 표준 (Prometheus 가 host.docker.internal scrape — ADR-008).
DEMO_SERVICES=(postgres redis prometheus loki tempo grafana otel-collector alertmanager auth-stub jaeger)

note "docker compose up -d (이미 떠있으면 멱등) — 데모 scope: ${DEMO_SERVICES[*]}"
# `set -e` 회피 — 한두 컨테이너가 port 충돌로 실패해도 나머지 단계는 의미가 있다 (개별
# health-check 가 뒤에서 실 상태를 진단). 진짜로 필수인 컨테이너는 step 1 끝의 ping 으로 검증.
docker compose "${COMPOSE_FILES[@]}" up -d "${DEMO_SERVICES[@]}" || warn "일부 컨테이너 기동 실패 — 다음 단계의 health-check 결과 확인"

# auth-stub 의 healthcheck 가 ready 될 때까지 짧게 대기
for i in $(seq 1 30); do
    if curl -fsS "${AUTH_STUB_URL}/healthz" >/dev/null 2>&1; then
        ok "auth-stub ready (${AUTH_STUB_URL})"
        break
    fi
    sleep 1
    [[ $i -eq 30 ]] && warn "auth-stub healthcheck 미응답 — 계속 진행"
done

# JWK Set 이 정상 응답하는지
if curl -fsS "${AUTH_STUB_URL}/.well-known/jwks.json" | python3 -c "import sys, json; d=json.load(sys.stdin); assert d['keys'][0]['kid'].startswith('minishop-demo-'), d" >/dev/null 2>&1; then
    ok "JWK Set 응답 OK — kid 확인"
else
    warn "JWK Set 응답 이상 — auth-stub 컨테이너 로그 확인 (docker logs minishop-auth-stub)"
fi

# Jaeger UI ping
if curl -fsS -o /dev/null -w "%{http_code}\n" "${JAEGER_URL}" 2>/dev/null | grep -qE "^(200|301|302)"; then
    ok "Jaeger UI 응답 (${JAEGER_URL})"
else
    warn "Jaeger UI 미응답 — 첫 기동은 ~10s 소요"
fi

# ---------- 2. Service liveness check ----------
step "2) 3 개 service liveness"

SERVICES_UP=true
for label in "order:${ORDER_URL}" "payment:${PAYMENT_URL}" "inventory:${INVENTORY_URL}"; do
    name="${label%%:*}"
    url="${label#*:}"
    if curl -fsS -m 2 "${url}/actuator/health" >/dev/null 2>&1; then
        ok "${name}-service up — ${url}"
    else
        warn "${name}-service 미기동 — ${url}/actuator/health 응답 없음"
        SERVICES_UP=false
    fi
done

if [[ "${SERVICES_UP}" != "true" ]]; then
    cat <<'MSG'

  [hint] 3 개 service 는 stack 외부 (host 의 ./gradlew bootRun) 에서 띄운다.
         Prometheus 가 host.docker.internal 로 scrape 하기 때문 (ADR-008 참조).
         아래를 각각 다른 셸에서 실행한 뒤 본 스크립트를 다시:
             cd services/order-service     && ./gradlew bootRun   # :8081
             cd services/payment-service   && ./gradlew bootRun   # :8082
             cd services/inventory-service && ./gradlew bootRun   # :8083
         3-7) 단계는 service 가 떠있어야 의미가 있어 여기서 종료.

MSG
    print_summary() {
        step "URL summary"
        echo "  Grafana       : ${GRAFANA_URL}    (admin / admin)"
        echo "  Prometheus    : ${PROMETHEUS_URL}/alerts"
        echo "  Tempo         : ${TEMPO_URL}"
        echo "  Jaeger UI     : ${JAEGER_URL}"
        echo "  Loki          : ${LOKI_URL}/ready"
        echo "  auth-stub JWKS: ${AUTH_STUB_URL}/.well-known/jwks.json"
    }
    print_summary
    exit 0
fi

# ---------- 3. mock JWT + traceparent → POST /orders ----------
step "3) mock JWT + traceparent → POST /orders"

# HS256 mock JWT — 다른 7 sister repo 에서는 RS256 + auth-stub 의 JWK Set 으로 검증하는 자리.
# 본 레포의 multi-service 는 JWT 를 *검증하지 않는다* — 헤더에 실어 보내 trace 와 같이 흐르게 만 함.
# 진짜 검증은 sister repo 의 application 설정에서 jwk-set-uri 를 auth-stub 으로 가리킬 때.
mint_mock_jwt() {
    local secret="${1:-demo-shared-secret-not-for-prod}"
    local header_json='{"alg":"HS256","typ":"JWT","kid":"demo-hs256"}'
    local now; now="$(date +%s)"
    local payload_json
    payload_json=$(python3 -c "import json,sys; print(json.dumps({'iss':'auth-stub','sub':'demo-user-42','aud':'minishop','iat':$now,'exp':$now+300,'scope':'order:create'}))")
    b64u() { openssl base64 -A | tr -d '=' | tr '/+' '_-'; }
    local h p sig
    h=$(printf '%s' "$header_json" | b64u)
    p=$(printf '%s' "$payload_json" | b64u)
    sig=$(printf '%s.%s' "$h" "$p" | openssl dgst -sha256 -hmac "$secret" -binary | b64u)
    printf '%s.%s.%s' "$h" "$p" "$sig"
}

# W3C traceparent — 32-hex trace_id + 16-hex span_id + 01 (sampled flag).
# OTel SDK 가 inbound 헤더에서 이 값을 받아 컨텍스트로 채택 → MDC / span 에 같은 trace_id 가 흐른다.
hex() { openssl rand -hex "$1"; }
TRACE_ID="$(hex 16)"   # 32 hex = 128 bit
SPAN_ID="$(hex 8)"     # 16 hex = 64 bit
TRACEPARENT="00-${TRACE_ID}-${SPAN_ID}-01"
JWT="$(mint_mock_jwt)"

note "trace_id: ${TRACE_ID}"
note "JWT (HS256, demo only): ${JWT:0:20}...${JWT: -8}"

ORDER_BODY='{"userId":42,"items":[{"productId":1001,"quantity":1,"price":9990}]}'

set +e
ORDER_RESP="$(curl -sS -i \
    -X POST "${ORDER_URL}/orders" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT}" \
    -H "traceparent: ${TRACEPARENT}" \
    --data "${ORDER_BODY}")"
ORDER_CURL_RC=$?
set -e

if [[ $ORDER_CURL_RC -ne 0 ]]; then
    warn "POST /orders curl 실패 (rc=${ORDER_CURL_RC}) — order-service 로그 확인"
else
    STATUS_LINE="$(printf '%s' "${ORDER_RESP}" | head -1)"
    OUTCOME_HEADER="$(printf '%s' "${ORDER_RESP}" | grep -i '^X-Order-Outcome:' | head -1 | tr -d '\r')"
    ok "응답 status: ${STATUS_LINE}"
    [[ -n "${OUTCOME_HEADER}" ]] && note "${OUTCOME_HEADER}"
fi

# Tempo 에 trace 가 도착하는 데 ~1-2s (OTel batch + tail_sampling decision_wait 10s 의 영향은
# 비-error / 비-slow 라 random 1% 에 떨어질 수 있음). 데모 신뢰성을 위해 동일 trace_id 로
# 한 번 더 살짝 다른 호출 — 어떤 sample policy 든 한 번은 잡히도록 (slow 가 아니더라도
# 표본 1% 에 들어갈 확률이 누적).
note "trace 가 sample 에서 빠질 수 있어 같은 trace_id 로 추가 호출 1 회"
curl -sS -o /dev/null \
    -X POST "${ORDER_URL}/orders" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT}" \
    -H "traceparent: ${TRACEPARENT}" \
    --data "${ORDER_BODY}" || true

cat <<EOM

  같은 trace_id 로 다음을 각각 열어 비교:
    Tempo      : ${TEMPO_URL}/api/traces/${TRACE_ID}
    Jaeger UI  : ${JAEGER_URL}/trace/${TRACE_ID}
    Grafana    : ${GRAFANA_URL}/explore  (Tempo datasource 에서 trace_id=${TRACE_ID})
    Loki 쿼리  : {application="order-service"} |= "${TRACE_ID}"
                 → correlation-mdc-starter 가 서버 로그의 [trace_id/span_id] 자리에 자동 주입
EOM

# ---------- 4. N+1 트리거 (slow-query-detector) ----------
step "4) N+1 패턴 트리거 (slow-query-detector)"

# GET /orders 는 의도적으로 N+1 — items lazy fetch. slow-query-detector 의 n_plus_one_total
# 카운터가 임계 (기본 5 회) 도달 순간마다 +1.
note "GET /orders?size=20 — items 가 order 마다 별도 SELECT (lazy)"
COUNTER_BEFORE=$(curl -fsS "${ORDER_URL}/actuator/prometheus" 2>/dev/null \
    | awk '/^n_plus_one_total/ && !/^#/ {sum+=$2} END {print sum+0}')
note "n_plus_one_total (before): ${COUNTER_BEFORE}"

# 한 번으론 임계가 안 차도록 N+1 유도를 위해 최소한 5 건 이상의 order 가 DB 에 있어야 한다.
# 위 단계에서 이미 1-2 건 만들었으니, 부족분을 채워서 listing 결과가 임계를 넘게.
for i in 1 2 3 4 5; do
    curl -sS -o /dev/null \
        -X POST "${ORDER_URL}/orders" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${JWT}" \
        -H "traceparent: 00-$(hex 16)-$(hex 8)-01" \
        --data "${ORDER_BODY}" || true
done

curl -sS -o /dev/null "${ORDER_URL}/orders?size=20" || true
sleep 2

COUNTER_AFTER=$(curl -fsS "${ORDER_URL}/actuator/prometheus" 2>/dev/null \
    | awk '/^n_plus_one_total/ && !/^#/ {sum+=$2} END {print sum+0}')
note "n_plus_one_total (after):  ${COUNTER_AFTER}"

if [[ "${COUNTER_AFTER}" -gt "${COUNTER_BEFORE}" ]]; then
    ok "N+1 감지 +$((COUNTER_AFTER - COUNTER_BEFORE)) — slow-query-detector 동작 확인"
else
    warn "n_plus_one_total 변화 없음 — DB 의 기존 order 수 / 임계 (mini-shop.slow-query.n-plus-one-threshold) 점검"
fi

cat <<EOM
  Grafana 패널: ${GRAFANA_URL}/d/slow-query-n-plus-one  (Slow Query & N+1 대시보드, ROADMAP Phase3 Step2)
  Prometheus 쿼리: rate(n_plus_one_total[1m])
EOM

# ---------- 5. JFR ad-hoc dump (jfr-recorder-starter) ----------
step "5) JFR ad-hoc dump (jfr-recorder-starter)"

JFR_TAG="integration-demo-$(date +%H%M%S)"
note "POST /actuator/jfr/${JFR_TAG} — chunk 즉시 dump"

set +e
JFR_RESP_CODE="$(curl -sS -o /tmp/.jfr-resp -w '%{http_code}' -X POST "${ORDER_URL}/actuator/jfr/${JFR_TAG}")"
set -e

if [[ "${JFR_RESP_CODE}" =~ ^2 ]]; then
    ok "ad-hoc dump 성공 (HTTP ${JFR_RESP_CODE})"
    if [[ -s /tmp/.jfr-resp ]]; then
        note "응답 본문 (앞 200 자):"
        head -c 200 /tmp/.jfr-resp; echo
    fi
else
    warn "ad-hoc dump 응답 HTTP ${JFR_RESP_CODE} — endpoint 노출 (management.endpoints.web.exposure.include=jfr) 확인"
fi

note "현재 보유 chunk 목록 — GET /actuator/jfr"
curl -fsS "${ORDER_URL}/actuator/jfr" 2>/dev/null | python3 -m json.tool 2>/dev/null | head -30 || warn "/actuator/jfr 응답 파싱 실패"

# ---------- 6. URL summary ----------
step "6) URL 요약"
cat <<EOM
  Grafana       : ${GRAFANA_URL}    (admin / admin)
  Prometheus    : ${PROMETHEUS_URL}/alerts
  Tempo trace   : ${TEMPO_URL}/api/traces/${TRACE_ID}
  Jaeger UI    : ${JAEGER_URL}/trace/${TRACE_ID}
  Loki          : ${LOKI_URL}/ready  (Grafana Explore 에서 {application="order-service"} |= "${TRACE_ID}")
  auth-stub JWKS: ${AUTH_STUB_URL}/.well-known/jwks.json
  auth-stub OIDC: ${AUTH_STUB_URL}/.well-known/openid-configuration

  사용된 trace_id : ${TRACE_ID}
  생성된 JFR tag  : ${JFR_TAG}
EOM
echo
ok "통합 데모 완료 — 위 URL 들에서 같은 trace_id / 메트릭 / chunk 가 한 흐름으로 보이면 성공."
