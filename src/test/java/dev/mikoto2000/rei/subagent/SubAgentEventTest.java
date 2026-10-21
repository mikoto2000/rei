package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import dev.mikoto2000.rei.ui.projection.DefaultAgentUiProjection;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 補助エージェントイベントの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SubAgentEventTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 親表示用の状態を保持することを確認する。 */
  @Test
  void shellShowsChildEventsAndPreservesParentProjection() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var output = new StringBuilder();
    var renderer =
        new ShellAgentEventRenderer(
            new ShellEventOutput() {
              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void print(String value) {
                output.append(value);
              }

              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void println(String value) {
                output.append(value).append('\n');
              }

              /** 検証で保留している出力の確定を扱う。 */
              public void flush() {}
            });
    var projection = new DefaultAgentUiProjection();
    projection.apply(factory.runStarted("parent", "task", null));
    try (var scope =
        AgentRunScope.open(new AgentRunContext("child", "subagent:child", directory))) {
      var start =
          factory.subAgentLifecycle(
              AgentEventType.SUBAGENT_STARTED,
              "parent",
              "child",
              "reviewer",
              "task",
              "RUNNING",
              0,
              null);
      renderer.onEvent(start);
      projection.apply(start);
      var tool = factory.toolStarted("tool", "readMultiFile", "file arguments");
      renderer.onEvent(factory.llmRequestStarted("child", "request", "subagent"));
      renderer.onEvent(tool);
      projection.apply(tool);
      var done =
          factory.subAgentLifecycle(
              AgentEventType.SUBAGENT_COMPLETED,
              "parent",
              "child",
              "reviewer",
              "task",
              "COMPLETED",
              1200,
              null);
      renderer.onEvent(done);
      projection.apply(done);
    }
    assertThat(output.toString())
        .contains("[subagent] reviewer", "completed")
        .contains(
            "[subagent:reviewer/child] [llm] request sent (subagent)",
            "[subagent:reviewer/child]   → readMultiFile file arguments");
    assertThat(projection.currentState().run().runId()).isEqualTo("parent");
    assertThat(projection.currentState().tools()).isEmpty();
  }

  /** 補助エージェントへの委譲で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void failedToolEventRedactsCredentials() {
    var events = new java.util.ArrayList<AgentEvent>();
    var callback =
        new org.springframework.ai.tool.ToolCallback() {
          /** 検証用に、補助エージェントへの委譲で参照するツール定義を返す。 */
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("test")
                .description("test")
                .inputSchema("{}")
                .build();
          }

          /** 検証用に、補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            throw new IllegalStateException("password=hidden " + "x".repeat(1000));
          }
        };
    assertThatThrownBy(
        () ->
            new ToolEventCallbackDecorator(
                    callback, new AgentEventFactory(Clock.systemUTC()), events::add)
                .call("{}"));
    assertThat(events.toString()).doesNotContain("hidden").doesNotContain("x".repeat(121));
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellSeparatesInterleavedRunsAndResumesParentAnswer() {
    var startedAt = java.time.Instant.parse("2026-08-23T00:00:00Z");
    var factory = new AgentEventFactory(Clock.fixed(startedAt, java.time.ZoneOffset.UTC));
    var output = new StringBuilder();
    var renderer =
        new ShellAgentEventRenderer(
            new ShellEventOutput() {
              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void print(String value) {
                output.append(value);
              }

              /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
              public void println(String value) {
                output.append(value).append('\n');
              }

              /** 検証で保留している出力の確定を扱う。 */
              public void flush() {}
            });
    renderer.onEvent(factory.messageStarted("parent-message", "assistant"));
    renderer.onEvent(factory.messageDelta("parent-message", "parent-before"));
    for (String run : java.util.List.of("first", "second")) {
      try (var scope = AgentRunScope.open(new AgentRunContext(run, "subagent:" + run, directory))) {
        renderer.onEvent(
            factory.subAgentLifecycle(
                AgentEventType.SUBAGENT_STARTED,
                "parent",
                run,
                "reviewer",
                "task",
                "RUNNING",
                0,
                null));
        renderer.onEvent(factory.llmRequestStarted(run, "request", "subagent-" + run));
      }
    }
    for (String run : java.util.List.of("first", "second", "unknown")) {
      try (var scope = AgentRunScope.open(new AgentRunContext(run, "subagent:" + run, directory))) {
        renderer.onEvent(factory.llmResponseFirstToken(run, "request", 10));
        renderer.onEvent(factory.llmResponseCompleted(run, "request", 20));
        renderer.onEvent(
            factory.llmRequestFailed(run, "request", 30, new IllegalStateException("failure")));
        renderer.onEvent(factory.toolCompleted("tool", "webSearch", 40, "result"));
        renderer.onEvent(
            factory.toolFailed(
                "tool", "webSearch", new ErrorInformation("error", "failure", null)));
        renderer.onEvent(factory.messageStarted("child-message", "assistant"));
        renderer.onEvent(factory.messageDelta("child-message", "child-answer"));
        renderer.onEvent(factory.messageCompleted("child-message", "assistant", "child-answer"));
      }
    }
    // Notifications are intentionally deferred while the parent answer is streaming.
    assertThat(output.toString()).doesNotContain("[subagent:", "child-answer");
    renderer.onEvent(factory.messageDelta("parent-message", "parent-after"));
    renderer.onEvent(
        factory.messageCompleted("parent-message", "assistant", "parent-beforeparent-after"));
    for (String run : java.util.List.of("first", "second", "unknown")) {
      try (var scope = AgentRunScope.open(new AgentRunContext(run, "subagent:" + run, directory))) {
        renderer.onRecentEvent(factory.toolStarted("tool", "webSearch", "{}"));
      }
      String prefix = "[subagent:" + (run.equals("unknown") ? "" : "reviewer/") + run + "] ";
      assertThat(output.toString())
          .contains(
              prefix + "[llm] first token (10 ms)",
              prefix
                  + "[llm] response received ("
                  + (run.equals("unknown") ? "request" : "subagent-" + run)
                  + ", 20 ms)",
              prefix
                  + "[llm] request failed (30 ms): [invalid_state] 処理に必要な状態が整っていません。保存済みの状態と接続先の状態を確認してください。",
              prefix + "  ✓ webSearch (40 ms)",
              prefix + "  ✗ webSearch: failure",
              prefix + "tool.started webSearch");
    }
    assertThat(output.toString())
        .startsWith(
            "=== answer ("
                + java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(startedAt)
                + ") ===\nparent-beforeparent-after\n")
        .doesNotContain("child-answer");
  }

  /** 補助エージェントへの委譲で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void lifecycleRoundTripsThroughExistingProjectEventStore() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var store = new ProjectAgentEventStore(directory);
    var owner =
        new AgentRunContext(
            "child", "subagent:child", directory, java.util.UUID.randomUUID().toString());
    var event =
        factory
            .subAgentLifecycle(
                AgentEventType.SUBAGENT_FAILED,
                "parent",
                "child",
                "reviewer",
                "password=hidden",
                "TIMEOUT",
                1200,
                "TIMEOUT")
            .withOwnership(owner);
    store.append(event);
    var restored = new ProjectAgentEventStore(directory).recent(owner.projectId(), 1).getFirst();
    assertThat(restored.payload()).isEqualTo(event.payload());
    assertThat(restored.runId()).isEqualTo("child");
    assertThat(restored.correlationId()).isEqualTo("parent");
    assertThat(restored.toString()).doesNotContain("hidden");
  }
}
