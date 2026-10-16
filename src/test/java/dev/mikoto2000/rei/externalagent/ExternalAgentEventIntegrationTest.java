package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ExternalAgentLifecyclePayload;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.ui.projection.DefaultAgentUiProjection;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class ExternalAgentEventIntegrationTest {
  @TempDir Path root;

  @Test
  void typedEventsRoundTripAndDoNotReplaceParentRun() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    String projectId = java.util.UUID.randomUUID().toString();
    var owner = new AgentRunContext("parent", "chat", root, projectId);
    var store = new ProjectAgentEventStore(root);
    var projection = new DefaultAgentUiProjection();
    projection.apply(factory.runStarted("parent", "user-request", null).withOwnership(owner));
    var before = projection.currentState();
    var event =
        factory
            .delegation(
                AgentEventType.DELEGATION_COMPLETED,
                "parent",
                new ExternalAgentLifecyclePayload(
                    "delegation", "codex", "review", "SUCCESS", "2 findings", 10, 0))
            .withOwnership(owner);
    store.append(event);
    var restored = store.recent(projectId, 1).getFirst();
    assertEquals(event.payload(), restored.payload());
    assertEquals("delegation", restored.correlationId());
    projection.apply(restored);
    assertEquals(before.run(), projection.currentState().run());
    var output = mock(ShellEventOutput.class);
    new ShellAgentEventRenderer(output).onEvent(restored);
    verify(output).println("[delegation] codex completed: 2 findings");
  }
}
