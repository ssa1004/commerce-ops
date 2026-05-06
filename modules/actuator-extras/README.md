# actuator-extras

Spring Boot Actuator에 운영에서 자주 필요한 인사이트를 추가하는 커스텀 endpoint 모음.

## 왜 만드나

- 기본 Actuator는 `/health`, `/metrics` 정도. 운영 중 *지금 무슨 일이 일어나는지* 알기엔 부족
- Hikari의 active/idle/pending 상태, 트랜잭션 깊이, 스레드풀 큐 상태 등은 메트릭으로도 보지만 *지금 이 순간*을 보고 싶을 때가 많음

## 추가할 Endpoint (계획)

| Endpoint | 내용 |
|---|---|
| `/actuator/hikari` | 풀별 active/idle/pending/total wait time, 최근 슬로우 acquire |
| `/actuator/threadpools` | 모든 `ThreadPoolTaskExecutor` 상태 (active, queue size, rejected count) |
| `/actuator/transactions` | 진행 중 트랜잭션 (시작 시간, 호출 스택 일부) |
| `/actuator/dependencies` | DB/Kafka/Redis ping + 최근 latency |

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
