# Case Studies — Index

운영하면서 직접 부딪힌 (또는 chaos로 만든) 케이스의 회고. 시간순/태그별로 모읍니다.

## By Date

| 날짜 | 제목 | 태그 | severity |
|---|---|---|---|
| 2026-05-07 | [결제 호출이 timeout으로 끊기는 동안 결제는 이미 SUCCESS였다](2026-05-07-payment-timeout-race.md) | timeout, in-doubt, distributed-systems | P1 |

## By Tag

### `timeout`
- [2026-05-07 — payment timeout race](2026-05-07-payment-timeout-race.md)

### `in-doubt` / `distributed-systems`
- [2026-05-07 — payment timeout race](2026-05-07-payment-timeout-race.md)

### `latency-spike`
- [2026-05-07 — payment timeout race](2026-05-07-payment-timeout-race.md)

### `gc` / `memory-leak` / `n+1` / `hikari-pool` / `kafka-lag` / `deadlock`
*(아직 없음 — 카오스 시나리오를 추가할 때마다 채워집니다)*

## 작성 규칙

- 새 케이스는 [_template.md](_template.md) 복사해서 시작
- 파일명: `YYYY-MM-DD-<short-slug>.md`
- 작성 후 이 INDEX.md의 By Date / By Tag 양쪽에 링크 추가
- 30분 이상 든 트러블슈팅은 케이스로 남기기
