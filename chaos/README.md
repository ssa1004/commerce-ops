# Chaos Scenarios

장애를 의도적으로 주입해 시스템 행동을 관찰합니다. 각 시나리오는 별도 markdown 파일로:

```
## 시나리오
## 가설 (이게 일어났을 때 어떻게 될 것 같다)
## 실행 절차
## 관찰 포인트 (Grafana 패널 / Trace 쿼리)
## 실제 결과
## 배운 점 → case-studies/ 로 옮길지 판단
```

## 카테고리

- **Network**: 지연 / 패킷 손실 / 단절
- **Resource**: CPU 스로틀 / 메모리 압박 / 디스크 풀
- **Dependency**: DB 연결 고갈 / Redis 다운 / Kafka broker kill
- **Application**: 메서드 지연 (chaos-injector 모듈)

## TODO (Phase 4)

- [ ] network-delay.md — payment-service ↔ 외부 PG 지연
- [ ] db-exhaust.md — Hikari pool 고갈
- [ ] kafka-broker-kill.md — Kafka broker 중지 시 consumer 동작
- [ ] redis-down.md — inventory-service 캐시 의존성 단절
- [ ] gc-pressure.md — 큰 객체 할당으로 GC 압박
