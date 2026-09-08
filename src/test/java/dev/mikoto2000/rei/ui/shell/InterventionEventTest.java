package dev.mikoto2000.rei.ui.shell;

import java.time.Clock;
import dev.mikoto2000.rei.event.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterventionEventTest {
  @Test void receivedAndAppliedAreTypedAndRendered() {
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
