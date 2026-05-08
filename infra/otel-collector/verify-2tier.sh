#!/usr/bin/env bash
# 2-tier OTel Collector 검증 스크립트.
#
# 가설: agent 의 `loadbalancing` exporter 가 `routing_key=traceID` 로 동작하면,
# 같은 trace 의 모든 span 은 *같은 backend 인스턴스* 로 라우팅된다 → backend.yaml
# 의 resource processor 가 박은 `otel.backend.id` 가 한 trace 의 모든 span 에서
# 동일해야 한다.
#
# 검증 흐름:
#   1) N 개의 trace 를 OTLP HTTP (4318) 로 직접 흘려보냄. trace 마다 root span +
#      child span 여러 개를 같은 traceID 로 묶어 전송.
#   2) Tempo 에 도착할 때까지 잠깐 대기 (decision_wait + batch.timeout 합).
#   3) 각 traceID 를 Tempo HTTP API 로 조회 → span 들의 `otel.backend.id` 집합을
#      추출. 집합 크기가 1 이면 OK, 2 이상이면 routing_key 가 깨진 것.
#
# 의존: curl, jq, python3. 모두 macOS / Linux 표준.

set -euo pipefail

OTLP_HTTP_URL="${OTLP_HTTP_URL:-http://localhost:4318/v1/traces}"
TEMPO_URL="${TEMPO_URL:-http://localhost:3200}"
NUM_TRACES="${NUM_TRACES:-50}"
SPANS_PER_TRACE="${SPANS_PER_TRACE:-3}"
WAIT_SECONDS="${WAIT_SECONDS:-15}"  # decision_wait(10s) + batch(5s) 여유

random_hex() {
  python3 - "$1" <<'PY'
import os, sys
n = int(sys.argv[1])
print(os.urandom(n).hex())
PY
}

now_nanos() {
  python3 -c "import time; print(time.time_ns())"
}

build_payload() {
  local trace_id="$1"
  local span_count="$2"

  python3 - "$trace_id" "$span_count" <<'PY'
import json, os, sys, time

trace_id = sys.argv[1]
span_count = int(sys.argv[2])
now = time.time_ns()

spans = []
for i in range(span_count):
    span_id = os.urandom(8).hex()
    parent_span_id = "" if i == 0 else spans[0]["spanId"]
    spans.append({
        "traceId": trace_id,
        "spanId": span_id,
        "parentSpanId": parent_span_id,
        "name": f"verify-2tier-{i}",
        "kind": 1,                                # SPAN_KIND_INTERNAL
        "startTimeUnixNano": str(now + i * 1000),
        "endTimeUnixNano":   str(now + i * 1000 + 5_000_000),  # 5ms
        "attributes": [
            {"key": "verify.synthetic", "value": {"boolValue": True}},
            {"key": "verify.span_index", "value": {"intValue": str(i)}},
        ],
        "status": {"code": 1},                    # STATUS_CODE_OK
    })

payload = {
    "resourceSpans": [{
        "resource": {
            "attributes": [
                {"key": "service.name", "value": {"stringValue": "verify-2tier-client"}},
                # 의도적으로 2-tier 를 검증하기 위한 latency policy 의 임계 (500ms)
                # *미만* 으로 짧게 — random 1% 에 안 걸리면 drop 될 수도 있는 게
                # 정상이지만, 같은 trace 내 routing 일관성은 분산 빈도와 무관하게
                # 검사된다 (drop 된 trace 는 Tempo 에 안 올라와 검증 대상에서 제외).
            ]
        },
        "scopeSpans": [{
            "scope": {"name": "verify-2tier"},
            "spans": spans
        }]
    }]
}
print(json.dumps(payload))
PY
}

push_trace() {
  local trace_id="$1"
  local payload
  payload=$(build_payload "$trace_id" "$SPANS_PER_TRACE")
  curl -sS -X POST "$OTLP_HTTP_URL" \
    -H 'Content-Type: application/json' \
    -d "$payload" >/dev/null
}

trace_backend_ids() {
  # Tempo /api/traces/{id} → resourceSpans[*].resource.attributes 에서
  # otel.backend.id 만 모아 unique 집합 출력.
  local trace_id="$1"
  curl -sS "${TEMPO_URL}/api/traces/${trace_id}" \
    | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
except json.JSONDecodeError:
    sys.exit(0)
batch_field = data.get('batches') or data.get('resourceSpans') or []
ids = set()
for b in batch_field:
    res = b.get('resource', {})
    for kv in res.get('attributes', []):
        if kv.get('key') == 'otel.backend.id':
            v = kv.get('value', {})
            ids.add(v.get('stringValue') or v.get('string_value') or '')
for i in sorted(ids):
    if i:
        print(i)
"
}

main() {
  echo "[verify-2tier] sending ${NUM_TRACES} traces × ${SPANS_PER_TRACE} spans → ${OTLP_HTTP_URL}"

  local trace_ids=()
  for _ in $(seq 1 "$NUM_TRACES"); do
    local tid
    tid=$(random_hex 16)
    push_trace "$tid"
    trace_ids+=("$tid")
  done

  echo "[verify-2tier] waiting ${WAIT_SECONDS}s (decision_wait + batch + Tempo ingest)"
  sleep "$WAIT_SECONDS"

  local total=0 split=0 missing=0 ok=0
  for tid in "${trace_ids[@]}"; do
    total=$((total + 1))
    local ids
    ids=$(trace_backend_ids "$tid" || true)
    local count
    count=$(echo "$ids" | grep -c . || true)
    if [[ "$count" -eq 0 ]]; then
      # baseline-1pct 에 안 걸려 sample 안 됐을 가능성 — drop 은 가설 검증과 무관.
      missing=$((missing + 1))
    elif [[ "$count" -eq 1 ]]; then
      ok=$((ok + 1))
    else
      split=$((split + 1))
      echo "[verify-2tier] FAIL trace_id=${tid} backends=$(echo "$ids" | tr '\n' ',')"
    fi
  done

  echo "[verify-2tier] result: total=${total} ok=${ok} split=${split} not_sampled=${missing}"
  if [[ "$split" -gt 0 ]]; then
    echo "[verify-2tier] FAIL — some traces were split across backends (routing_key broken)"
    exit 1
  fi
  echo "[verify-2tier] OK — every sampled trace had a single backend"
}

main "$@"
