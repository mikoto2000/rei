package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 補助エージェントツール一覧の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SubAgentToolsTest {
  /** 自動委譲データ形式について、旧形式を保持することを確認する。 */
  @Test
  void automaticDelegationSchemaPreservesLegacyAndAddsExplicitDestination() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var schema =
        new SubAgentTools(mock(SubAgentRunner.class), registry)
            .callback()
            .getToolDefinition()
            .inputSchema();
    assertThat(schema)
        .contains(
            "agent",
            "task",
            "context",
            "backendId",
            "accountRef",
            "modelId",
            "sourceRefs",
            "constraints",
            "completionCriteria",
            "expectedOutput");
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void consensusCallbacksExposeHashReferencesAndDefaultDisabled() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var tools = new SubAgentTools(mock(SubAgentRunner.class), registry);
    assertThat(tools.callback("compareSubAgentAnswers").getToolDefinition().inputSchema())
        .contains("childId", "resultHash");
    assertThat(tools.callback("judgeSubAgentAnswers").getToolDefinition().inputSchema())
        .contains("agent", "references");
    assertThatThrownBy(() -> tools.compareSubAgentAnswers(null, null))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 依存関係完了時の処理一覧外部への公開上限内に収めた計画およびについて、設定済みサービスを必要とすることを確認する。 */
  @Test
  void dagCallbacksExposeBoundedPlanAndRequireConfiguredService() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var tools = new SubAgentTools(mock(SubAgentRunner.class), registry);
    assertThat(tools.callback("delegateTaskGraph").getToolDefinition().inputSchema())
        .contains("nodes", "dependencies", "failurePolicy", "maxConcurrency");
    assertThat(tools.callback("getSubAgentGraph").getToolDefinition().inputSchema())
        .contains("graphId");
    assertThat(tools.callback("resumeSubAgentGraph").getToolDefinition().inputSchema())
        .contains("revision");
    assertThatThrownBy(() -> tools.delegateTaskGraph(null, null))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 永続化された完了時の処理一覧について、実際の実行識別情報要求を利用することを確認する。 */
  @Test
  void durableCallbacksUseActualRunIdentityRequestAndSharedBudget() throws Exception {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var runner = mock(SubAgentRunner.class);
    var tools = new SubAgentTools(runner, registry);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext("parent", "session", directory, "project");
    var factory = new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
    var run =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "parent",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 3),
            null,
            factory,
            event -> {});
    run.setRunContext(owner);
    run.setUserRequest("subagent resume child-id 7");
    var context =
        new org.springframework.ai.chat.model.ToolContext(
            java.util.Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
    when(runner.resumeDurable(eq(owner), eq("child-id"), eq(7L), eq(run.userRequest()), any()))
        .thenReturn(
            new SubAgentResult(
                "reviewer",
                "child-run",
                SubAgentResult.Status.COMPLETED,
                "done",
                Instant.now(),
                Instant.now()));
    String result =
        tools.callback("resumeSubAgent").call("{\"childId\":\"child-id\",\"revision\":7}", context);
    assertThat(result).contains("child-run");
    verify(runner).resumeDurable(eq(owner), eq("child-id"), eq(7L), eq(run.userRequest()), any());
    when(runner.durableChildren(owner, 0, 10)).thenReturn(java.util.List.of());
    assertThat(tools.callback("listDurableSubAgents").call("{\"offset\":0,\"limit\":10}", context))
        .isEqualTo("[]");
    verify(runner).durableChildren(owner, 0, 10);
  }

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 明示的な実行を呼び出し元へ引き継ぐことを確認する。 */
  @Test
  void mixedParallelToolPropagatesExplicitRunAcrossSchedulerBoundary() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var parallel = mock(ParallelSubAgentDelegator.class);
    var tools = new SubAgentTools(mock(SubAgentRunner.class), registry);
    tools.parallelDelegator(parallel);
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext("parent", "session", directory, "project");
    var run =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "parent",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 3),
            null,
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    run.setRunContext(owner);
    var context =
        new org.springframework.ai.chat.model.ToolContext(
            java.util.Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
    when(parallel.delegate(anyList(), any()))
        .thenAnswer(
            call -> {
              assertThat(dev.mikoto2000.rei.core.chat.AgentRunScope.current()).isEqualTo(owner);
              return new ParallelSubAgentDelegator.Batch(
                  ParallelSubAgentDelegator.Status.FAILED, java.util.List.of());
            });
    tools.delegateTasks(
        java.util.List.of(
            new ParallelSubAgentDelegator.Request(
                "plan",
                "reviewer",
                "task",
                null,
                "chatgpt-plan",
                "personal",
                "model",
                java.util.List.of(),
                "{\"unverified\":[]}")),
        context);
  }

  /** 外部完了時の処理について、実際の実行を利用することを確認する。 */
  @Test
  void externalCallbackUsesActualRunAndExplicitMaterialsOnly() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var runner = mock(SubAgentRunner.class);
    var callback = new SubAgentTools(runner, registry).callback();
    var owner =
        new dev.mikoto2000.rei.core.chat.AgentRunContext("parent", "session", directory, "project");
    var run =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
            "parent",
            new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 3),
            null,
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            event -> {});
    run.setRunContext(owner);
    var context =
        new org.springframework.ai.chat.model.ToolContext(
            java.util.Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
    callback.call(
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"accountRef\":\"personal\",\"modelId\":\"model\",\"sourceRefs\":[\"docs/design.md\"],\"expectedOutput\":\"{\\\"unverified\\\":[]}\"}",
        context);
    var request = org.mockito.ArgumentCaptor.forClass(ChatGptSubAgentBackend.Request.class);
    verify(runner).runAutomaticBackend(request.capture(), eq(owner), any());
    assertThat(request.getValue().sources()).containsExactly("docs/design.md");
    assertThat(request.getValue().account()).isEqualTo("personal");
    verify(runner, never()).run(anyString(), anyString(), any());
    assertThatThrownBy(
            () ->
                callback.call(
                    "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"paid\"}", context))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                callback.call(
                    "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\"}"))
        .isInstanceOf(RuntimeException.class);
  }

  /** 補助エージェントへの委譲で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelCallbackMapsRequestsAndSerializesOrderedIndividualResults() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var parallel = mock(ParallelSubAgentDelegator.class);
    var tools = new SubAgentTools(mock(SubAgentRunner.class), registry);
    tools.parallelDelegator(parallel);
    var request = new ParallelSubAgentDelegator.Request("first", "reviewer", "review", null);
    var child =
        new SubAgentResult(
            "reviewer",
            "run",
            SubAgentResult.Status.COMPLETED,
            "done",
            Instant.now(),
            Instant.now());
    when(parallel.delegate(java.util.List.of(request)))
        .thenReturn(
            new ParallelSubAgentDelegator.Batch(
                ParallelSubAgentDelegator.Status.COMPLETED,
                java.util.List.of(
                    new ParallelSubAgentDelegator.Item(
                        "first", "reviewer", ParallelSubAgentDelegator.Status.COMPLETED, child))));
    var callback = tools.callback("delegateTasks");
    assertThat(callback.getToolDefinition().inputSchema()).contains("requests", "task", "agent");
    var json =
        new SubAgentResultParser()
            .parse(
                callback.call(
                    "{\"requests\":[{\"id\":\"first\",\"agent\":\"reviewer\",\"task\":\"review\"}]}"));
    assertThat(json.at("/items/0/id").asString()).isEqualTo("first");
    assertThat(json.at("/items/0/result/subAgentRunId").asString()).isEqualTo("run");
    verify(parallel).delegate(java.util.List.of(request));
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void delegateReturnsTypedStatusesAndDynamicCatalogWithoutPrompts() throws Exception {
    var loader =
        new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")), m -> true);
    var registry = new SubAgentRegistry(directory, loader);
    var runner = mock(SubAgentRunner.class);
    var tools = new SubAgentTools(runner, registry);
    var callback = tools.callback();
    Files.writeString(
        directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    registry.reload();
    assertThat(callback.getToolDefinition().description())
        .contains("reviewer", "Independent review")
        .doesNotContain("Review independently.");
    for (var status : SubAgentResult.Status.values()) {
      when(runner.run("reviewer", "task", null))
          .thenReturn(
              new SubAgentResult(
                  "reviewer", "run", status, "output", Instant.now(), Instant.now()));
      assertThat(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}"))
          .contains(status.name());
    }
    verify(runner, times(SubAgentResult.Status.values().length)).run("reviewer", "task", null);
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void callbackSerializesValidatedEnvelopeAndStructuredErrors() {
    var registry =
        new SubAgentRegistry(
            directory, new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of()), m -> true));
    var runner = mock(SubAgentRunner.class);
    var callback = new SubAgentTools(runner, registry).callback();
    var parser = new SubAgentResultParser();
    var envelope = SubAgentOutput.fromValidated(parser.parse(SubAgentResultParserTest.VALID));
    when(runner.run("reviewer", "task", null))
        .thenReturn(
            new SubAgentResult(
                "reviewer",
                "run",
                SubAgentResult.Status.COMPLETED,
                SubAgentResultParserTest.VALID,
                Instant.now(),
                Instant.now(),
                envelope,
                java.util.List.of()));
    var json = parser.parse(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}"));
    assertThat(json.at("/structuredOutput/status").asString()).isEqualTo("SUCCESS");
    assertThat(json.at("/structuredOutput/result").isObject()).isTrue();
    when(runner.run("reviewer", "task", null))
        .thenReturn(
            new SubAgentResult(
                "reviewer",
                "run",
                SubAgentResult.Status.FAILED,
                "SubAgent structural validation failed",
                Instant.now(),
                Instant.now(),
                null,
                java.util.List.of(
                    new ValidationError(
                        "/result/findings", "Schema constraint violated: required"))));
    json = parser.parse(callback.call("{\"agent\":\"reviewer\",\"task\":\"task\"}"));
    assertThat(json.at("/status").asString()).isEqualTo("FAILED");
    assertThat(json.at("/validationErrors/0/path").asString()).isEqualTo("/result/findings");
  }
}
