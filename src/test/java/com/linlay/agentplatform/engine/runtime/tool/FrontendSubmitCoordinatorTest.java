package com.linlay.agentplatform.engine.runtime.tool;

import com.linlay.agentplatform.config.properties.FrontendToolProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrontendSubmitCoordinatorTest {

    @Test
    void submitShouldReturnDirectParams() {
        FrontendToolProperties properties = new FrontendToolProperties();
        properties.setSubmitTimeoutMs(3_000);
        FrontendSubmitCoordinator coordinator = new FrontendSubmitCoordinator(properties);

        Mono<Object> pending = coordinator.awaitSubmit("run_1", "tool_1").cache();
        pending.subscribe();

        Map<String, Object> params = Map.of(
                "selectedOption", "杭州西湖一日游",
                "selectedIndex", 1
        );
        FrontendSubmitCoordinator.SubmitAck ack = coordinator.submit("run_1", "tool_1", params);

        assertThat(ack.accepted()).isTrue();
        assertThat(ack.status()).isEqualTo("accepted");
        assertThat(pending.block(Duration.ofSeconds(1))).isEqualTo(params);
    }

    @Test
    void submitShouldReturnUnmatchedWhenNoPendingTool() {
        FrontendToolProperties properties = new FrontendToolProperties();
        FrontendSubmitCoordinator coordinator = new FrontendSubmitCoordinator(properties);

        FrontendSubmitCoordinator.SubmitAck ack = coordinator.submit(
                "run_missing",
                "tool_missing",
                Map.of("confirmed", true)
        );

        assertThat(ack.accepted()).isFalse();
        assertThat(ack.status()).isEqualTo("unmatched");
        assertThat(ack.detail()).contains("No pending frontend tool found");
    }

    @Test
    void cancelRunShouldFailPendingSubmitWaiters() {
        FrontendToolProperties properties = new FrontendToolProperties();
        properties.setSubmitTimeoutMs(3_000);
        FrontendSubmitCoordinator coordinator = new FrontendSubmitCoordinator(properties);

        Mono<Object> pending = coordinator.awaitSubmit("run_cancel", "tool_1").cache();
        pending.subscribe(null, error -> {});

        coordinator.cancelRun("run_cancel");

        assertThatThrownBy(() -> pending.block(Duration.ofSeconds(1)))
                .isInstanceOf(CancellationException.class)
                .hasMessageContaining("Run interrupted");
    }
}
