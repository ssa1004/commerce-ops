package io.minishop.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAGA 모델의 상태 전이를 *Spring StateMachine 만* 로딩한 슬림 컨텍스트에서 검증.
 *
 * <p>{@code @SpringBootTest} 를 쓰지 않는다 — 그건 outer 의 {@code @SpringBootApplication}
 * 을 자동 검색해 함께 끌어와 의도와 어긋남 (전체 컨텍스트 부팅, 외부 의존 필요). 대신
 * {@code @ExtendWith(SpringExtension)} + {@code @ContextConfiguration} 으로 *정확히 명시한*
 * config 만 로딩.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderSagaConfigTests.SagaTestContext.class)
class OrderSagaConfigTests {

    @Autowired
    StateMachineFactory<OrderSagaStates, OrderSagaEvents> factory;

    private StateMachine<OrderSagaStates, OrderSagaEvents> machine;

    @BeforeEach
    void setUp() {
        machine = factory.getStateMachine();
        machine.startReactively().block();
    }

    @AfterEach
    void tearDown() {
        if (machine != null) machine.stopReactively().block();
    }

    @Test
    @DisplayName("happy path: DRAFT → INVENTORY_RESERVING → INVENTORY_RESERVED → PAYMENT_CHARGING → PAID")
    void happyPath() {
        send(OrderSagaEvents.ORDER_CREATED);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.INVENTORY_RESERVING);

        send(OrderSagaEvents.INVENTORY_OK);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.INVENTORY_RESERVED);

        send(OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.PAYMENT_CHARGING);

        send(OrderSagaEvents.PAYMENT_OK);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.PAID);
    }

    @Test
    @DisplayName("재고 부족 — INVENTORY_OUT_OF_STOCK 으로 즉시 FAILED (보상 없음)")
    void outOfStock_skipsCompensation() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_OUT_OF_STOCK);
        // OUT_OF_STOCK 은 잡힌 게 없는 케이스 — COMPENSATING 거치지 않고 바로 FAILED.
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("재고 인프라 실패 — INVENTORY_INFRA_ERROR → COMPENSATING → FAILED")
    void inventoryInfra_goesThroughCompensating() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_INFRA_ERROR);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.COMPENSATING);

        send(OrderSagaEvents.COMPENSATION_DONE);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("결제 거절 — PAYMENT_DECLINED → COMPENSATING → FAILED")
    void paymentDeclined_goesThroughCompensating() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_OK);
        send(OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        send(OrderSagaEvents.PAYMENT_DECLINED);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.COMPENSATING);

        send(OrderSagaEvents.COMPENSATION_DONE);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("결제 인프라 실패 — PAYMENT_INFRA_ERROR → COMPENSATING → FAILED")
    void paymentInfra_goesThroughCompensating() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_OK);
        send(OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        send(OrderSagaEvents.PAYMENT_INFRA_ERROR);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.COMPENSATING);

        send(OrderSagaEvents.COMPENSATION_DONE);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("upstream limited (재고 단계) — UPSTREAM_LIMITED → COMPENSATING → FAILED")
    void upstreamLimited_inventory() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.UPSTREAM_LIMITED);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.COMPENSATING);

        send(OrderSagaEvents.COMPENSATION_DONE);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("upstream limited (결제 단계) — PAYMENT_CHARGING 에서도 동일하게 보상")
    void upstreamLimited_payment() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_OK);
        send(OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        send(OrderSagaEvents.UPSTREAM_LIMITED);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.COMPENSATING);

        send(OrderSagaEvents.COMPENSATION_DONE);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.FAILED);
    }

    @Test
    @DisplayName("정의되지 않은 transition — 상태 유지, machine 이 조용히 무시")
    void unhandledEvent_keepsState() {
        send(OrderSagaEvents.ORDER_CREATED);
        // INVENTORY_RESERVING 에서 PAYMENT_OK 는 정의되지 않은 transition.
        send(OrderSagaEvents.PAYMENT_OK);
        // 상태가 변하면 안 됨.
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.INVENTORY_RESERVING);
    }

    @Test
    @DisplayName("종결 상태 (PAID) 후의 추가 이벤트는 무시 — terminal 보호")
    void paidIsTerminal() {
        send(OrderSagaEvents.ORDER_CREATED);
        send(OrderSagaEvents.INVENTORY_OK);
        send(OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        send(OrderSagaEvents.PAYMENT_OK);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.PAID);

        // 종결 후 또 PAYMENT_OK — 변하면 안 됨.
        send(OrderSagaEvents.PAYMENT_OK);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.PAID);
    }

    private void send(OrderSagaEvents event) {
        machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast();
    }

    @Configuration
    @Import(OrderSagaConfig.class)
    static class SagaTestContext {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
