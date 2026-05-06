# Load Testing — k6

[k6](https://k6.io/) 부하 시나리오 모음. 메트릭은 자동으로 Prometheus로도 보낼 수 있음.

## 시나리오

| 파일 | 목적 | 패턴 |
|---|---|---|
| `baseline.js` | 현재는 `/actuator/health` smoke, Phase 2에서 주문 happy path로 전환 | 50 VUs, 5분 |
| `peak.js` | 현재는 `/actuator/health` peak smoke, Phase 2에서 이벤트성 주문 부하로 전환 | 5분 ramp-up → 200 VUs 10분 → ramp-down |
| `soak.js` | 현재는 `/actuator/health` soak smoke, Phase 2에서 장시간 주문 부하로 전환 | 30 VUs, 2시간 |

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

## TODO (Phase 2)

- [ ] baseline.js — POST /orders happy path
- [ ] peak.js — 200 VU 10분
- [ ] soak.js — 2시간
- [ ] 시나리오별 임계 (thresholds) 정의
- [ ] CI에서 smoke 부하 자동 실행
