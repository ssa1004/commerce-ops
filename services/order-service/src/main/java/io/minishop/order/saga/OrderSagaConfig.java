package io.minishop.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.EnumStateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * 주문 SAGA 의 상태 전이 모델. {@link EnableStateMachineFactory} 로 *factory* 형태 등록 —
 * 한 주문에 한 인스턴스를 띄워야 하므로 (각 주문이 독립적인 상태를 가지므로) singleton
 * StateMachine 이 아니라 factory 가 자연스럽다.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>모델만 정의, 외부 호출은 구동측 (coordinator) 책임.</b> 액션/가드 안에서 직접
 *       Inventory/PaymentClient 를 부르면 단위 테스트가 무거워진다 (Spring container + mock).
 *       모델은 *상태 전이만* 검증, 실 호출은 OrderService 가 그대로 가지고 있다.</li>
 *   <li><b>액션은 메트릭/로그 정도만.</b> 상태 진입/탈출 시점에 메트릭을 박아 *모델 자체* 가
 *       관측 가능한 산출물이 되도록.</li>
 *   <li><b>종결 상태 (PAID / FAILED) 명시.</b> StateMachine 은 종결 후에도 인스턴스가 살아
 *       있는데, 외부에서 *이 인스턴스가 끝났는지* 를 알 수 있어야 한다.</li>
 * </ul>
 *
 * <p>전이 규칙 (그래프):
 * <pre>
 *   DRAFT --ORDER_CREATED--> INVENTORY_RESERVING
 *   INVENTORY_RESERVING --INVENTORY_OK--> INVENTORY_RESERVED
 *   INVENTORY_RESERVING --INVENTORY_OUT_OF_STOCK--> FAILED  (보상 불필요 — 잡힌 게 없음)
 *   INVENTORY_RESERVING --INVENTORY_INFRA_ERROR--> COMPENSATING (부분 잡힘 가능)
 *   INVENTORY_RESERVING --UPSTREAM_LIMITED--> COMPENSATING (부분 잡힘 가능)
 *   INVENTORY_RESERVED --(PAYMENT 진행)--> PAYMENT_CHARGING
 *   PAYMENT_CHARGING --PAYMENT_OK--> PAID
 *   PAYMENT_CHARGING --PAYMENT_DECLINED--> COMPENSATING
 *   PAYMENT_CHARGING --PAYMENT_INFRA_ERROR--> COMPENSATING
 *   PAYMENT_CHARGING --UPSTREAM_LIMITED--> COMPENSATING
 *   COMPENSATING --COMPENSATION_DONE--> FAILED
 * </pre>
 *
 * <p>주의: INVENTORY_RESERVED → PAYMENT_CHARGING 는 *내부 자동 전이* — 별도 이벤트 없이
 * 다음 step 으로 진행하는 모양이라 {@code PAYMENT_OK} 같은 이벤트 없이 계속 흐른다. 본
 * 모델에서는 *자동 전이* 대신 명시 이벤트로 표현 — coordinator 가 PAYMENT 호출 직전에
 * `sendEvent(ORDER_CREATED)` 같은 트리거를 보내는 식으로 단순화. 모델 검증의 의도는 *상태
 * 별로 받아들일 수 있는 이벤트* 가 정확히 한정되어 있는지를 보는 것.
 */
@Configuration
@EnableStateMachineFactory
public class OrderSagaConfig extends EnumStateMachineConfigurerAdapter<OrderSagaStates, OrderSagaEvents> {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaConfig.class);

    private final MeterRegistry meterRegistry;

    public OrderSagaConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderSagaStates, OrderSagaEvents> states) throws Exception {
        states
                .withStates()
                .initial(OrderSagaStates.DRAFT)
                .states(EnumSet.allOf(OrderSagaStates.class))
                .end(OrderSagaStates.PAID)
                .end(OrderSagaStates.FAILED);
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderSagaStates, OrderSagaEvents> transitions)
            throws Exception {
        transitions
                // DRAFT — 주문 생성과 동시에 재고 잡기 단계로.
                .withExternal()
                    .source(OrderSagaStates.DRAFT)
                    .target(OrderSagaStates.INVENTORY_RESERVING)
                    .event(OrderSagaEvents.ORDER_CREATED)
                    .action(traceAction("order_created"))
                .and()
                // 재고 OK — 결제로.
                .withExternal()
                    .source(OrderSagaStates.INVENTORY_RESERVING)
                    .target(OrderSagaStates.INVENTORY_RESERVED)
                    .event(OrderSagaEvents.INVENTORY_OK)
                .and()
                .withExternal()
                    .source(OrderSagaStates.INVENTORY_RESERVED)
                    .target(OrderSagaStates.PAYMENT_CHARGING)
                    .event(OrderSagaEvents.PAYMENT_CHARGE_STARTED)
                    .action(traceAction("payment_charging_enter"))
                .and()
                // 재고 부족 — 잡힌 게 없으므로 보상 없이 종결.
                .withExternal()
                    .source(OrderSagaStates.INVENTORY_RESERVING)
                    .target(OrderSagaStates.FAILED)
                    .event(OrderSagaEvents.INVENTORY_OUT_OF_STOCK)
                    .action(failAction("out_of_stock"))
                .and()
                // 재고 인프라 실패 — 부분 잡힘 가능 → 보상.
                .withExternal()
                    .source(OrderSagaStates.INVENTORY_RESERVING)
                    .target(OrderSagaStates.COMPENSATING)
                    .event(OrderSagaEvents.INVENTORY_INFRA_ERROR)
                    .action(compensateAction("inventory_infra"))
                .and()
                .withExternal()
                    .source(OrderSagaStates.INVENTORY_RESERVING)
                    .target(OrderSagaStates.COMPENSATING)
                    .event(OrderSagaEvents.UPSTREAM_LIMITED)
                    .action(compensateAction("upstream_limited_inventory"))
                .and()
                // 결제 OK — 종결 PAID.
                .withExternal()
                    .source(OrderSagaStates.PAYMENT_CHARGING)
                    .target(OrderSagaStates.PAID)
                    .event(OrderSagaEvents.PAYMENT_OK)   // semantics: "결제 응답 OK"
                    .action(succeedAction())
                .and()
                // 결제 거절 — 잡은 재고 풀어야 → 보상.
                .withExternal()
                    .source(OrderSagaStates.PAYMENT_CHARGING)
                    .target(OrderSagaStates.COMPENSATING)
                    .event(OrderSagaEvents.PAYMENT_DECLINED)
                    .action(compensateAction("payment_declined"))
                .and()
                .withExternal()
                    .source(OrderSagaStates.PAYMENT_CHARGING)
                    .target(OrderSagaStates.COMPENSATING)
                    .event(OrderSagaEvents.PAYMENT_INFRA_ERROR)
                    .action(compensateAction("payment_infra"))
                .and()
                .withExternal()
                    .source(OrderSagaStates.PAYMENT_CHARGING)
                    .target(OrderSagaStates.COMPENSATING)
                    .event(OrderSagaEvents.UPSTREAM_LIMITED)
                    .action(compensateAction("upstream_limited_payment"))
                .and()
                // 보상 완료 — 종결 FAILED.
                .withExternal()
                    .source(OrderSagaStates.COMPENSATING)
                    .target(OrderSagaStates.FAILED)
                    .event(OrderSagaEvents.COMPENSATION_DONE)
                    .action(failAction("compensation_done"));
    }

    private Action<OrderSagaStates, OrderSagaEvents> traceAction(String tag) {
        return ctx -> {
            log.debug("saga.transition tag={} from={} to={}", tag, ctx.getSource().getId(), ctx.getTarget().getId());
            meterRegistry.counter("order.saga.transitions", "tag", tag).increment();
        };
    }

    private Action<OrderSagaStates, OrderSagaEvents> compensateAction(String reason) {
        return ctx -> {
            log.info("saga.compensate reason={} from={} to={}", reason, ctx.getSource().getId(), ctx.getTarget().getId());
            meterRegistry.counter("order.saga.compensations", "reason", reason).increment();
        };
    }

    private Action<OrderSagaStates, OrderSagaEvents> succeedAction() {
        return ctx -> meterRegistry.counter("order.saga.outcomes", "outcome", "paid").increment();
    }

    private Action<OrderSagaStates, OrderSagaEvents> failAction(String reason) {
        return ctx -> meterRegistry.counter("order.saga.outcomes", "outcome", "failed", "reason", reason).increment();
    }
}
