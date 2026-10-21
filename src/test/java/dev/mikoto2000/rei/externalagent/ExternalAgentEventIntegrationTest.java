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

/** 外部エージェントイベント外部連携の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ExternalAgentEventIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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
