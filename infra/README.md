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

## Reset Local Volumes

로컬 데이터 (DB·Kafka·Loki·Tempo 의 영속 볼륨) 를 버려도 될 때만 사용합니다.

```bash
docker compose -f infra/docker-compose.yml down -v
```
