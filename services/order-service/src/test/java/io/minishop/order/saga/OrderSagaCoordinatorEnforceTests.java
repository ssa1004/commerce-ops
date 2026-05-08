package io.minishop.order.saga;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minishop.order.exception.OrchestrationException;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * coordinator 의 *enforce* 모드 — mismatch 가 즉시 IllegalStateException.
 * 운영에서 켜면 OrderService 가 확정 후 이 호출이 throw 하므로 *모델 버그가 즉시 사고로*
 * 인지된다. CI/staging 에서만 켤 옵션.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderSagaCoordinatorEnforceTests.EnforceContext.class)
@TestPropertySource(properties = {
        "app.saga.machine.enforce=true"
})
class OrderSagaCoordinatorEnforceTests {

    @Autowired
    OrderSagaCoordinator coordinator;

    @Test
    void mismatch_inEnforceMode_throws() {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = coordinator.begin(10L);
        assertThatThrownBy(() -> coordinator.assertConsistent(machine, null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAGA state mismatch");
    }

    @Test
    void agreement_inEnforceMode_doesNotThrow() {
        StateMachine<OrderSagaStates, OrderSagaEvents> machine = coordinator.begin(11L);
        coordinator.apply(machine, OrderSagaEvents.INVENTORY_OUT_OF_STOCK);
        // OUT_OF_STOCK → FAILED — outcome=OUT_OF_STOCK, isPaid=false 와 일치 → throw 없음.
        coordinator.assertConsistent(machine, OrchestrationException.Outcome.OUT_OF_STOCK, false);
    }

    @Configuration
    @Import({OrderSagaConfig.class, OrderSagaCoordinator.class})
    static class EnforceContext {
        @Bean
        MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    }
}
