package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.ui.shell.*;
import dev.mikoto2000.rei.ui.projection.*;

class SubAgentEventTest {
  @TempDir Path directory;
  @Test void shellSummarizesChildWithoutShowingIntermediateToolsAndPreservesParentProjection() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var output = new StringBuilder();
    var renderer = new ShellAgentEventRenderer(new ShellEventOutput() {
      public void print(String value) { output.append(value); }
      public void println(String value) { output.append(value).append('\n'); }
      public void flush() { }
    });
    var projection = new DefaultAgentUiProjection();
    projection.apply(factory.runStarted("parent", "task", null));
    try (var scope = AgentRunScope.open(new AgentRunContext("child", "subagent:child", directory))) {
      var start = factory.subAgentLifecycle(AgentEventType.SUBAGENT_STARTED, "parent", "child", "reviewer", "task", "RUNNING", 0, null);
      renderer.onEvent(start);
      projection.apply(start);
      var tool = factory.toolStarted("tool", "readMultiFile", "private intermediate arguments");
      renderer.onEvent(tool);
      projection.apply(tool);
      var done = factory.subAgentLifecycle(AgentEventType.SUBAGENT_COMPLETED, "parent", "child", "reviewer", "task", "COMPLETED", 1200, null);
      renderer.onEvent(done);
      projection.apply(done);
    }
    assertThat(output.toString()).contains("[subagent] reviewer", "completed").doesNotContain("private intermediate");
    assertThat(projection.currentState().run().runId()).isEqualTo("parent");
    assertThat(projection.currentState().tools()).isEmpty();
  }
  @Test void failedToolEventRedactsCredentials() {
    var events = new java.util.ArrayList<AgentEvent>();
    var callback = new org.springframework.ai.tool.ToolCallback() {
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder().name("test").description("test").inputSchema("{}").build();
      }
      public String call(String input) { throw new IllegalStateException("password=hidden " + "x".repeat(1000)); }
    };
    assertThatThrownBy(() -> new ToolEventCallbackDecorator(callback, new AgentEventFactory(Clock.systemUTC()), events::add).call("{}"));
    assertThat(events.toString()).doesNotContain("hidden").doesNotContain("x".repeat(121));
  }
  @Test void lifecycleRoundTripsThroughExistingProjectEventStore() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var store = new ProjectAgentEventStore(directory);
    var owner = new AgentRunContext("child", "subagent:child", directory, java.util.UUID.randomUUID().toString());
    var event = factory.subAgentLifecycle(AgentEventType.SUBAGENT_FAILED, "parent", "child", "reviewer",
        "password=hidden", "TIMEOUT", 1200, "TIMEOUT").withOwnership(owner);
    store.append(event);
    var restored = new ProjectAgentEventStore(directory).recent(owner.projectId(), 1).getFirst();
    assertThat(restored.payload()).isEqualTo(event.payload());
    assertThat(restored.runId()).isEqualTo("child");
    assertThat(restored.correlationId()).isEqualTo("parent");
    assertThat(restored.toString()).doesNotContain("hidden");
  }
}
