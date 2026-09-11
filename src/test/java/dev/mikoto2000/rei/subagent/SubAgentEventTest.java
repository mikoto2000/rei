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
  @Test void shellShowsChildEventsAndPreservesParentProjection() {
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
      var tool = factory.toolStarted("tool", "readMultiFile", "file arguments");
      renderer.onEvent(factory.llmRequestStarted("child", "request", "subagent"));
      renderer.onEvent(tool);
      projection.apply(tool);
      var done = factory.subAgentLifecycle(AgentEventType.SUBAGENT_COMPLETED, "parent", "child", "reviewer", "task", "COMPLETED", 1200, null);
      renderer.onEvent(done);
      projection.apply(done);
    }
    assertThat(output.toString()).contains("[subagent] reviewer", "completed").contains("[subagent:reviewer/child] [llm] request sent (subagent)", "[subagent:reviewer/child]   → readMultiFile file arguments");
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
  @Test void shellSeparatesInterleavedRunsAndResumesParentAnswer() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var output = new StringBuilder();
    var renderer = new ShellAgentEventRenderer(new ShellEventOutput() {
      public void print(String value) { output.append(value); }
      public void println(String value) { output.append(value).append('\n'); }
      public void flush() { }
    });
    renderer.onEvent(factory.messageStarted("parent-message", "assistant"));
    renderer.onEvent(factory.messageDelta("parent-message", "parent-before"));
    for (String run : java.util.List.of("first", "second")) {
      try (var scope = AgentRunScope.open(new AgentRunContext(run, "subagent:" + run, directory))) {
        renderer.onEvent(factory.subAgentLifecycle(AgentEventType.SUBAGENT_STARTED,
            "parent", run, "reviewer", "task", "RUNNING", 0, null));
      }
    }
    for (String run : java.util.List.of("first", "second", "unknown")) {
      try (var scope = AgentRunScope.open(new AgentRunContext(run, "subagent:" + run, directory))) {
        renderer.onEvent(factory.llmResponseFirstToken(run, "request", 10));
        renderer.onEvent(factory.llmResponseCompleted(run, "request", 20));
        renderer.onEvent(factory.llmRequestFailed(run, "request", 30, new IllegalStateException("failure")));
        renderer.onEvent(factory.toolCompleted("tool", "webSearch", 40, "result"));
        renderer.onEvent(factory.toolFailed("tool", "webSearch", new ErrorInformation("error", "failure", null)));
        renderer.onEvent(factory.messageStarted("child-message", "assistant"));
        renderer.onEvent(factory.messageDelta("child-message", "child-answer"));
        renderer.onEvent(factory.messageCompleted("child-message", "assistant", "child-answer"));
        renderer.onRecentEvent(factory.toolStarted("tool", "webSearch", "{}"));
      }
      String prefix = "[subagent:" + (run.equals("unknown") ? "" : "reviewer/") + run + "] ";
      assertThat(output.toString()).contains(
          prefix + "[llm] first token (10 ms)",
          prefix + "[llm] response received (20 ms)",
          prefix + "[llm] request failed (30 ms): failure",
          prefix + "  ✓ webSearch (40 ms)",
          prefix + "  ✗ webSearch: failure",
          prefix + "tool.started webSearch");
    }
    renderer.onEvent(factory.messageDelta("parent-message", "parent-after"));
    renderer.onEvent(factory.messageCompleted("parent-message", "assistant", "parent-beforeparent-after"));
    assertThat(output.toString()).startsWith("=== answer ===\nparent-before\n").endsWith("parent-after\n")
        .doesNotContain("child-answer");
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
