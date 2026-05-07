# chaos-injector

메서드 단위로 지연·실패·예외를 주입하는 작은 라이브러리. **데모 환경 전용**.

## 사용 시나리오

- "결제 외부 API가 1% 확률로 5xx 떨어지면?"
- "재고 조회가 평균 500ms 지연되면 p99가 어떻게 되나?"
- 운영 환경엔 절대 의존성으로 넣지 않을 것 (`mini-shop.chaos.enabled` 기본 false)

## 사용법 (계획)

```java
@Service
class PaymentClient {
    @Chaos(name = "external-pg", latency = "200ms±50ms", failureRate = 0.01)
    public PgResult charge(...) { ... }
}
```

또는 외부에서 동적으로:

```bash
curl -X POST localhost:8082/chaos/external-pg \
  -d '{"latency": "1s", "failureRate": 0.5}'
```

## Configuration

```yaml
mini-shop:
  chaos:
    enabled: true        # 데모 환경에서만 true
    profile-restriction: [demo, dev]   # 외 환경에선 자동 disable
```

## TODO

- [ ] AOP (메서드 호출 앞뒤에 별도 로직을 끼우는 기법) 기반 인터셉터
- [ ] HTTP endpoint 로 동적 조절 (재시작 없이 강도 변경)
- [ ] 분포 옵션 (uniform — 균등, normal — 정규, exponential — 지수)
- [ ] Reactor (리액티브 라이브러리) 호환
- [ ] 안전 가드 (`production` profile 에서 강제 비활성화)
