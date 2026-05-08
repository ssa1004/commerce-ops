package io.minishop.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.statemachine.StateMachine;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * coordinator 의 *shadow* 모드 동작 — mismatch 가 발생해도 throw 하지 않고 메트릭만.
 *
 * <p>{@link OrderSagaCoordinatorEnforceTests} 가 enforce 모드의 throw 동작을 별도 검증.
 * 컨텍스트는 한 클래스당 하나로 둬 격리. SpringBootTest 가 아니라 명시 ContextConfiguration —
 * outer 의 SpringBootApplication 자동 검색을 막아 외부 의존 (DB / Kafka) 없이 가볍게 돈다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderSagaCoordinatorTests.SagaCoordinatorTestContext.class)
@TestPropertySource(properties = {
        "app.saga.machine.enforce=false"
})
class OrderSagaCoordinatorTests {

    @Autowired
    OrderSagaCoordinator coordinator;

    @Autowired
    MeterRegistry meters;

    @Test
    void mismatch_inShadowMode_doesNotThrow() {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = coordinator.begin(1L);
        // SAGA 가 INVENTORY_RESERVING 상태인데 outcome 은 PAID — 명확한 mismatch.
        // shadow 모드라 throw 없이 메트릭만 올라가야 한다.
        coordinator.assertConsistent(machine, null, true);
        assertThat(meters.counter("order.saga.consistency", "result", "mismatch",
                "expected", "paid", "actual", "INVENTORY_RESERVING").count()).isEqualTo(1.0);
    }

    @Test
    void unhandledEvent_recordsCounter() {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = coordinator.begin(2L);
        // RESERVING 에서 COMPENSATION_DONE 은 정의되지 않음.
        coordinator.apply(machine, OrderSagaEvents.COMPENSATION_DONE);
        assertThat(meters.counter("order.saga.unhandled",
                "state", "INVENTORY_RESERVING",
                "event", "COMPENSATION_DONE").count()).isEqualTo(1.0);
    }

    @Test
    void consistentEnding_recordsOk() {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = coordinator.begin(3L);
        coordinator.apply(machine, OrderSagaEvents.INVENTORY_OK);
        coordinator.apply(machine, OrderSagaEvents.PAYMENT_CHARGE_STARTED);
        coordinator.apply(machine, OrderSagaEvents.PAYMENT_OK);
        assertThat(machine.getState().getId()).isEqualTo(OrderSagaStates.PAID);
        coordinator.assertConsistent(machine, null, true);
        assertThat(meters.counter("order.saga.consistency", "result", "ok").count()).isEqualTo(1.0);
    }

    @Configuration
    @Import({OrderSagaConfig.class, OrderSagaCoordinator.class})
    static class SagaCoordinatorTestContext {
        @Bean
        MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }
}
