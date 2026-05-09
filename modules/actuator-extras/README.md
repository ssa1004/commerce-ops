# actuator-extras

> 📝 **설계 단계** — `src/` 없음, 의존성으로 추가해도 동작 코드 없음. ROADMAP Phase 3 Step 4 에서 구현 예정.

Spring Boot Actuator (Spring Boot 의 운영 상태 노출 모듈 — `/actuator/*` 경로) 에 운영에서 자주 필요한 정보를 추가하는 커스텀 엔드포인트 모음.

## 배경

- 기본 Actuator 는 `/health`, `/metrics` 정도. 운영 중 *지금 무슨 일이 일어나는지* 알기엔 부족
- Hikari (DB 커넥션 풀) 의 active/idle/pending 상태, 트랜잭션 깊이, 스레드풀 큐 상태 등은 메트릭으로도 보지만 *지금 이 순간* 의 스냅샷을 보고 싶을 때가 많음

## 추가할 Endpoint (계획)

| Endpoint | 내용 |
|---|---|
| `/actuator/hikari` | 풀별 active (사용 중) / idle (노는 중) / pending (커넥션 받으려고 줄 서있는 요청 수) / 누적 대기 시간, 최근 느렸던 acquire (커넥션 받아오는 데 시간이 오래 걸린 사례) |
| `/actuator/threadpools` | 모든 `ThreadPoolTaskExecutor` 상태 (active, queue size, rejected count — 큐가 가득 차서 거부된 작업 수) |
| `/actuator/transactions` | 진행 중 트랜잭션 (시작 시간, 호출 스택 일부) |
| `/actuator/dependencies` | DB/Kafka/Redis ping + 최근 응답 시간 |

## Configuration (예시)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus, hikari, threadpools, transactions, dependencies
mini-shop:
  actuator-extras:
    transactions:
      slow-threshold: 1s
      max-tracked: 100
```

## TODO

- [ ] Hikari endpoint
- [ ] ThreadPool endpoint
- [ ] Transaction tracker
- [ ] Dependency ping
- [ ] 권한 가드 (운영 환경 보안)
