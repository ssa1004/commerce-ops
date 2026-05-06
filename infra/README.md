# Infra

현재 단계에서 실제로 실행 가능한 Docker Compose 스택입니다. 서비스 컨테이너는 Phase 1에서 Spring Boot 프로젝트가 생성된 뒤 추가합니다.

## Included Services

| Service | Port | Purpose |
|---|---:|---|
| PostgreSQL | 5432 | `orderdb`, `paymentdb`, `inventorydb` 생성 |
| Redis | 6379 | 재고 캐시, 분산락 데모 |
| Kafka | 9092 | Phase 2 이벤트 흐름 |
| OpenTelemetry Collector | 4317, 4318 | OTLP 수신과 텔레메트리 라우팅 |
| Prometheus | 9090 | 메트릭 저장, 알람 룰 평가 |
| Loki | 3100 | 로그 저장 |
| Tempo | 3200 | 트레이스 저장 |
| Grafana | 3000 | 대시보드와 데이터소스 연동 |
| Alertmanager | 9093 | 알람 라우팅 |

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

Prometheus 컨테이너는 OTLP metrics와 remote-write traffic을 모두 받을 수 있게 설정되어 있습니다.

- OpenTelemetry Collector는 OTLP metrics를 `/api/v1/otlp/v1/metrics`로 보냅니다.
- Tempo metrics-generator와 k6는 remote-write metrics를 `/api/v1/write`로 보냅니다.

## Reset Local Volumes

로컬 데이터를 버려도 될 때만 사용합니다.

```bash
docker compose -f infra/docker-compose.yml down -v
```
