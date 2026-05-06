# payment-service

결제 처리. 외부 PG는 내부 mock 컨트롤러로 시뮬레이션 (지연·실패율 조절).

## API (Phase 1 목표)

| Method | Path | 설명 |
|---|---|---|
| POST | `/payments` | 결제 처리 |
| GET | `/payments/{id}` | 결제 조회 |

## 외부 PG Mock

`/mock-pg/charge` 엔드포인트가 환경변수에 따라:
- 평균 200ms 지연 (정규분포)
- 1% 확률 5xx
- 0.5% 확률 30s 지연 (timeout 데모)

→ Phase 2의 chaos 시나리오에서 이 수치를 동적으로 조절.

## TODO (Phase 1)

- [ ] Spring Boot 프로젝트 초기화
- [ ] `Payment` 엔티티 + Flyway
- [ ] `POST /payments` 동기 처리
- [ ] mock-pg 컨트롤러 (지연/실패 시뮬레이션)
- [ ] 메트릭: 결제 성공률, p99 응답시간
