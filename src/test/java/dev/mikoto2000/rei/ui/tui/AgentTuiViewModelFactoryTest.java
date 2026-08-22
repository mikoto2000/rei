package dev.mikoto2000.rei.ui.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.projection.DefaultAgentUiProjection;

class AgentTuiViewModelFactoryTest {

  private final AgentEventFactory events = new AgentEventFactory(
      Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
  private final AgentTuiViewModelFactory factory = new AgentTuiViewModelFactory();

  @Test
  void rendersEveryRunStatus() {
    DefaultAgentUiProjection projection = subscribedProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    assertEquals("IDLE", model(projection).status());
    bus.publish(events.runStarted("run-1", "user", null));
    assertEquals("RUNNING", model(projection).status());
    bus.publish(events.runCompleted("run-1", 10));
    assertEquals("COMPLETED", model(projection).status());
    bus.publish(events.runStarted("run-2", "user", null));
    bus.publish(events.runFailed("run-2", new ErrorInformation("IO", "bad", null)));
    assertEquals("FAILED", model(projection).status());
  }

  @Test
  void projectionStreamingSnapshotBecomesJapaneseAssistantText() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    bus.publish(events.messageStarted("msg-1", "assistant"));
    bus.publish(events.messageDelta("msg-1", "関連ファイルを"));
    assertEquals("関連ファイルを", model(projection).assistantText());
    bus.publish(events.messageDelta("msg-1", "確認しています。"));
    assertEquals("関連ファイルを確認しています。", model(projection).assistantText());
  }

  @Test
  void longAssistantMessageIsNotTruncatedByViewModel() {
    String text = "長いメッセージ".repeat(1000);
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    bus.publish(events.messageCompleted("msg-1", "assistant", text));

    assertEquals(text, model(projection).assistantText());
  }

  @Test
  void commandOutputTemporarilyOverridesAgentAssistantText() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    bus.publish(events.messageCompleted("msg-1", "assistant", "agent response"));

    AgentTuiRenderModel model = factory.create(
        projection.currentState(), new AgentTuiInput(), false, 10, "command\noutput");

    assertEquals("command\noutput", model.assistantText());
  }

  @Test
  void toolsShowStatusDurationErrorAndStartOrder() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    bus.publish(events.toolStarted("call-1", "grep", "q"));
    bus.publish(events.toolCompleted("call-1", "grep", 84, "ok"));
    bus.publish(events.toolStarted("call-2", "read", "file"));
    bus.publish(events.toolFailed("call-2", "read", new ErrorInformation("IO", "permission denied", null)));
    bus.publish(events.toolStarted("call-3", "edit", "file"));

    AgentTuiRenderModel model = model(projection);
    assertEquals("✓ grep  84 ms", model.toolLines().get(0));
    assertEquals("✗ read  permission denied", model.toolLines().get(1));
    assertEquals("→ edit", model.toolLines().get(2));
  }

  @Test
  void overflowingToolsKeepTheLatestExecutions() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    for (int index = 1; index <= 5; index++) {
      bus.publish(events.toolStarted("call-" + index, "tool-" + index, ""));
    }

    AgentTuiRenderModel model = factory.create(projection.currentState(), new AgentTuiInput(), false, 2);
    assertEquals(2, model.toolLines().size());
    assertTrue(model.toolLines().get(0).contains("tool-4"));
    assertTrue(model.toolLines().get(1).contains("tool-5"));
  }

  @Test
  void eventBusProjectionToRenderModelIntegrationDoesNotInterpretEventsInView() {
    DefaultAgentUiProjection projection = new DefaultAgentUiProjection();
    InMemoryAgentEventBus bus = busFor(projection);
    bus.publish(events.runStarted("run-1", "user", null));
    bus.publish(events.messageStarted("msg-1", "assistant"));
    bus.publish(events.messageDelta("msg-1", "streaming"));
    bus.publish(events.toolStarted("call-1", "search", "q"));

    AgentTuiRenderModel model = model(projection);
    assertEquals("RUNNING", model.status());
    assertEquals("streaming", model.assistantText());
    assertEquals("→ search", model.toolLines().getFirst());
  }

  private AgentTuiRenderModel model(DefaultAgentUiProjection projection) {
    return factory.create(projection.currentState(), new AgentTuiInput(), false, 20);
  }

  private DefaultAgentUiProjection subscribedProjection() {
    return new DefaultAgentUiProjection();
  }

  private InMemoryAgentEventBus busFor(DefaultAgentUiProjection projection) {
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(projection);
    return bus;
  }
}
