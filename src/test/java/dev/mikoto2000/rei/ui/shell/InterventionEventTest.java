package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class InterventionEventTest {
  @Test
  void receivedAndAppliedAreTypedAndRendered() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var output = mock(ShellEventOutput.class);
    var renderer = new ShellAgentEventRenderer(output);
    var received = factory.intervention("run", "input", "keep README", false);
    var applied = factory.intervention("run", "input", "keep README", true);
    assertThat(received.runId()).isEqualTo("run");
    assertThat(applied.type()).isEqualTo(AgentEventType.USER_INTERVENTION_APPLIED);
    renderer.onEvent(received);
    renderer.onEvent(applied);
    verify(output).println("[user] guidance queued");
    verify(output).println("[user] guidance applied");
  }
}
