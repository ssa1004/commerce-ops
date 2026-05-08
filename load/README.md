# Load Testing — k6

[k6](https://k6.io/) (JavaScript 로 시나리오를 작성하는 부하 테스트 도구) 부하 시나리오 모음. 메트릭은 자동으로 Prometheus 로도 보낼 수 있음.

## 시나리오

| 파일 | 목적 | 패턴 |
|---|---|---|
| `baseline.js` | 평상시 트래픽 — `POST /orders` 정상 흐름 | 50 VUs (가상 사용자), 5분 |
| `peak.js` | 이벤트성 피크 — `POST /orders` 정상 흐름 | 5분 ramp-up (점진 증가) → 200 VUs 10분 → ramp-down (점진 감소) |
| `soak.js` | 장시간 안정성 — `POST /orders` 정상 흐름 | 30 VUs, 2시간 |
| `health-only.js` | 비즈니스 엔드포인트 없이 `/actuator/health` 만 두드리는 기동 smoke | 1 VU, 30s |

201 (PAID) 외 409 (OUT_OF_STOCK) / 402 (PAYMENT_DECLINED) 는 의도된 비즈니스 결과로, 5xx 만 실패로 카운트한다.

## 실행

```bash
# 단일 실행
k6 run load/baseline.js

# Prometheus로 메트릭 송신
k6 run --out experimental-prometheus-rw=http://localhost:9090/api/v1/write load/baseline.js
```

`BASE_URL`로 대상 서비스를 바꿀 수 있습니다.

```bash
BASE_URL=http://localhost:8081 k6 run load/baseline.js
```

## TODO

- [ ] CI에서 smoke 부하 (`health-only.js`) 자동 실행
- [ ] 시나리오별 SLO 임계 재정의 (Phase 4 SLO 작업과 같이)
