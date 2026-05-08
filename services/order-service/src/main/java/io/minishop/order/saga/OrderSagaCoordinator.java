package io.minishop.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.minishop.order.exception.OrchestrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.stereotype.Component;

/**
 * 주문 SAGA 의 *모델 운영자* — {@link OrderSagaConfig} 가 정의한 모델로 한 주문에 대해
 * StateMachine 인스턴스를 띄우고, 외부 호출 결과를 이벤트로 변환해 transition 시킨다.
 *
 * <p>도입 단계 (현재): {@code OrderService} 의 기존 if/else 흐름은 *그대로 유지* 하되, 같은
 * 시점에 StateMachine 도 함께 진행 → 두 결정이 일치하는지를 메트릭으로 비교. 모델 도입의
 * <em>회귀 안전망</em>: 모델이 기존 흐름과 같은 결과를 내는지를 24/7 검증할 수 있다.
 *
 * <p>다음 단계 (후속): in-memory 인스턴스 → Postgres persistence ({@code StateMachinePersister})
 * 로 옮겨 *재시작 후에도 SAGA 진행 상태를 복구* 가능하게. 그 전에는 process 가 죽으면
 * 진행 중인 SAGA 가 사라짐 — 다만 기존 동기 흐름이 진실의 원천이라 도메인 정합은 영향 없음.
 *
 * <p>설계 결정:
 * <ul>
 *   <li><b>shadow mode</b> — {@code app.saga.machine.enforce=false} 일 때 mismatch 가 나도
 *       예외를 던지지 않는다 (메트릭만). 모델 자체에 버그가 있어도 운영 트래픽에 영향 없게.</li>
 *   <li><b>enforce mode</b> — {@code app.saga.machine.enforce=true} 일 때 mismatch 가 나면
 *       예외 → 운영자가 즉시 인지. CI/staging 에서만 켤 옵션.</li>
 *   <li><b>StateMachine factory</b> — Spring StateMachine 의 권장 구조. 한 SAGA 인스턴스마다
 *       새 machine — 인스턴스 격리.</li>
 * </ul>
 */
@Component
public class OrderSagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaCoordinator.class);

    private final StateMachineFactory<OrderSagaStates, OrderSagaEvents> factory;
    private final MeterRegistry meterRegistry;
    private final boolean enforce;

    public OrderSagaCoordinator(StateMachineFactory<OrderSagaStates, OrderSagaEvents> factory,
                                MeterRegistry meterRegistry,
                                @Value("${app.saga.machine.enforce:false}") boolean enforce) {
        this.factory = factory;
        this.meterRegistry = meterRegistry;
        this.enforce = enforce;
    }

    /**
     * 한 주문에 대해 새 SAGA 인스턴스를 시작하고 첫 이벤트 (ORDER_CREATED) 까지 보낸다.
     * 반환값은 *이미 ORDER_CREATED 가 처리된* StateMachine — 그 다음 이벤트 (재고/결제 응답) 는
     * 호출자가 보낸다.
     */
    public StateMachine<OrderSagaStates, OrderSagaEvents> begin(Long orderId) {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = factory.getStateMachine("order-" + orderId);
        machine.startReactively().block();
        machine.sendEvent(reactor.core.publisher.Mono.just(
                org.springframework.messaging.support.MessageBuilder
                        .withPayload(OrderSagaEvents.ORDER_CREATED).build()
        )).blockLast();
        return machine;
    }

    /**
     * 외부 호출 결과를 SAGA 이벤트로 보낸다. 이벤트가 현재 상태에서 정의되지 않은 transition
     * 이면 Spring StateMachine 이 *조용히 무시* 하므로, 우리가 직접 transition 적용 여부를
     * 검사해 메트릭으로 노출한다.
     */
    public void apply(StateMachine<OrderSagaStates, OrderSagaEvents> machine, OrderSagaEvents event) {
        OrderSagaStates before = machine.getState().getId();
        machine.sendEvent(reactor.core.publisher.Mono.just(
                org.springframework.messaging.support.MessageBuilder.withPayload(event).build()
        )).blockLast();
        OrderSagaStates after = machine.getState().getId();
        if (before == after && !isTerminal(before)) {
            // 정의되지 않은 transition — 모델이 현실보다 좁다는 신호.
            meterRegistry.counter("order.saga.unhandled",
                    "state", before.name(),
                    "event", event.name()).increment();
        }
    }

    /**
     * 기존 OrderService 의 결정 (Outcome) 과 SAGA 의 종결 상태가 일치하는지 검증.
     * shadow mode 면 메트릭만, enforce mode 면 예외.
     */
    public void assertConsistent(StateMachine<OrderSagaStates, OrderSagaEvents> machine,
                                 OrchestrationException.Outcome outcome,
                                 boolean isPaid) {
        OrderSagaStates state = machine.getState().getId();
        boolean machineSaysPaid = state == OrderSagaStates.PAID;
        boolean machineSaysFailed = state == OrderSagaStates.FAILED;

        boolean consistent =
                (isPaid && machineSaysPaid) ||
                (!isPaid && machineSaysFailed && outcome != null);

        if (consistent) {
            meterRegistry.counter("order.saga.consistency", "result", "ok").increment();
            return;
        }

        meterRegistry.counter("order.saga.consistency",
                "result", "mismatch",
                "expected", isPaid ? "paid" : (outcome == null ? "unknown_failed" : outcome.name()),
                "actual", state.name()).increment();
        log.warn("saga.mismatch expected={} actual={} (outcome={}, isPaid={})",
                state, state, outcome, isPaid);

        if (enforce) {
            throw new IllegalStateException(
                    "SAGA state mismatch: expected paid=" + isPaid + " outcome=" + outcome + " but machine=" + state);
        }
    }

    /** 종결 상태 판정 — unhandled 카운터에서 종결 후 추가 이벤트는 *비정상* 이 아니다 (정상 무시). */
    private boolean isTerminal(OrderSagaStates s) {
        return s == OrderSagaStates.PAID || s == OrderSagaStates.FAILED;
    }
}
