# Infra

현재 단계에서 실제로 실행 가능한 Docker Compose 스택입니다. 서비스 컨테이너는 Phase 1에서 Spring Boot 프로젝트가 생성된 뒤 추가합니다.

## Included Services

| Service | Port | Purpose |
|---|---:|---|
| PostgreSQL | 5432 | `orderdb`, `paymentdb`, `inventorydb` 생성 |
| Redis | 6379 | 재고 캐시, 분산락 (여러 인스턴스가 같은 자원에 동시 접근 못 하게 잠그는 도구) 데모 |
| Kafka | 9092 | Phase 2 이벤트 흐름 (메시지 브로커 — 발행/구독 채널) |
| OpenTelemetry Collector | 4317, 4318 | OTLP (OTel 의 표준 송신 프로토콜) 수신과 텔레메트리 라우팅 |
| Prometheus | 9090 | 메트릭 저장, 알람 룰 평가 |
| Loki | 3100 | 로그 저장 |
| Tempo | 3200 | 트레이스 저장 |
| Grafana | 3000 | 대시보드와 데이터소스 연결 |
| Alertmanager | 9093 | 알람 라우팅 (Prometheus 가 발화시킨 알람을 슬랙/이메일 등으로) |

## Run

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

Grafana는 <http://localhost:3000> 에서 `admin / admin`으로 접속할 수 있습니다.

## Validate Configs

```bash
docker compose -f infra/docker-compose.yml config
```

Prometheus 컨테이너는 OTLP metrics 와 remote-write (다른 시스템이 메트릭을 직접 푸시하는 방식) 트래픽을 모두 받을 수 있게 설정되어 있습니다.

- OpenTelemetry Collector 는 OTLP metrics 를 `/api/v1/otlp/v1/metrics` 로 보냅니다.
- Tempo metrics-generator (트레이스로부터 메트릭을 자동 생성하는 부가 기능) 와 k6 (부하 도구) 는 remote-write metrics 를 `/api/v1/write` 로 보냅니다.

## OTel Collector 2-tier (옵션)

기본은 단일 collector (`otel-collector` 한 컨테이너). 트래픽이 올라가 tail_sampling buffer 가 saturate 에 닿으면 *agent → backend pool* 의 2-tier 로 분리합니다.

```bash
docker compose \
  -f infra/docker-compose.yml \
  -f infra/docker-compose.collector-2tier.yml \
  up -d
```

핵심:

- agent (`agent.yaml`) 는 어플리케이션이 보내는 OTLP (4317/4318) 를 그대로 받아 `loadbalancing` exporter 로 backend pool 에 분배. `routing_key=traceID` 로 *consistent hashing* — 같은 trace 의 모든 span 은 *항상 같은 backend 인스턴스* 로 라우팅됩니다 (tail_sampling 의 의사결정이 정확하려면 한 trace 의 span 이 한 instance 메모리에 모여야 함).
- backend (`backend.yaml`) 가 tail_sampling 을 수행하고 Tempo 로 export. `OTEL_BACKEND_ID` 를 resource attribute 로 박아 검증 스크립트가 같은 trace 의 모든 span 이 한 backend 로 갔는지 실측할 수 있게 했습니다.

검증:

```bash
./infra/otel-collector/verify-2tier.sh
# [verify-2tier] result: total=50 ok=50 split=0 not_sampled=0
# [verify-2tier] OK — every sampled trace had a single backend
```

`split > 0` 이면 routing_key 가 깨진 것 — 코드 변경 후 회귀 테스트로 활용합니다. ADR-017 참고.

## Reset Local Volumes

로컬 데이터 (DB·Kafka·Loki·Tempo 의 영속 볼륨) 를 버려도 될 때만 사용합니다.

```bash
docker compose -f infra/docker-compose.yml down -v
```
