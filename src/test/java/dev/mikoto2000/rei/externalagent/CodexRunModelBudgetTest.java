package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Codex実行モデル予算の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CodexRunModelBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code output} の条件を再現するための補助情報を返す。 */
  String output(String usage) throws Exception {
    return output(usage, "{\"summary\":\"ok\",\"findings\":[],\"warnings\":[]}");
  }

  /** {@code output} の条件を再現するための補助情報を返す。 */
  String output(String usage, String message) throws Exception {
    var finalMessage =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "type",
                    "item.completed",
                    "item",
                    Map.of("type", "agent_message", "text", message)));
    return "{\"type\":\"turn.started\"}\n"
        + finalMessage
        + "\n{\"type\":\"turn.completed\",\"usage\":"
        + usage
        + "}";
  }

  /** {@code executor} の条件を再現するための補助情報を返す。 */
  ExternalAgentExecutor executor(String output, AtomicInteger paid) {
    return executor(
        new ExternalAgentProcessRunner.Output(
            ExternalAgentResult.Status.SUCCESS, output, "", 0, 1, false),
        paid);
  }

  /** {@code executor} の条件を再現するための補助情報を返す。 */
  ExternalAgentExecutor executor(ExternalAgentProcessRunner.Output output, AtomicInteger paid) {
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              Duration total,
              Duration idle,
              int limit,
              BooleanSupplier cancelled) {
            if (command.contains("--help"))
              return new Output(
                  ExternalAgentResult.Status.SUCCESS,
                  "--ignore-user-config --ignore-rules --strict-config --ephemeral --output-schema --json",
                  "",
                  0,
                  1,
                  false);
            paid.incrementAndGet();
            return output;
          }
        };
    return new CodexExternalAgentExecutor(new CodexProperties(), runner);
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  ExternalAgentDelegationService service(ExternalAgentExecutor executor) {
    var service =
        new ExternalAgentDelegationService(
            executor,
            new CommandCancellationService(),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {},
            Optional.empty());
    var properties = new CodexProperties();
    properties.setInheritRunModelBudget(true);
    service.modelBudgetProperties(properties);
    return service;
  }

  /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
  RunExecutionContext run(OutputLimitRunBudget budget) {
    var run = new RunExecutionContext("run", budget, null, null, null);
    run.setRunContext(new AgentRunContext("run", "chat", root, "p"));
    run.setUserRequest("Codex にレビューさせて");
    return run;
  }

  /** 外部エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void codexUsageChargesParentAndExactLimitBlocksNextModelCall() throws Exception {
    var paid = new AtomicInteger();
    var budget = new OutputLimitRunBudget(0, 10, null, 5);
    var run = run(budget);
    assertTrue(
        service(
                executor(
                    output("{\"input_tokens\":3,\"cached_input_tokens\":2,\"output_tokens\":2}"),
                    paid))
            .review(run, "review", null, "")
            .success());
    assertEquals(5, budget.totalTokens());
    assertEquals(1, paid.get());
    assertTrue(
        assertThrows(RuntimeException.class, run::consumeNextLlmCall)
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
  }

  /** 外部エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void exhaustedCallBudgetNeverStartsPaidCliAndOvershootIsCharged() throws Exception {
    var paid = new AtomicInteger();
    var executor = executor(output("{\"input_tokens\":3,\"output_tokens\":2}"), paid);
    var empty = run(new OutputLimitRunBudget(0, 0));
    assertTrue(
        assertThrows(
                RuntimeException.class, () -> service(executor).review(empty, "review", null, ""))
            .getMessage()
            .contains("LLM_CALL_BUDGET_EXCEEDED"));
    assertEquals(0, paid.get());
    var budget = new OutputLimitRunBudget(0, 10, null, 4);
    assertTrue(
        assertThrows(
                RuntimeException.class,
                () -> service(executor).review(run(budget), "review", null, ""))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(5, budget.totalTokens());
    assertEquals(1, paid.get());
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedNonpositiveAmbiguousAndIncompleteUsageFailClosed() throws Exception {
    var variants = new ArrayList<String>();
    for (String usage :
        List.of(
            "{}",
            "{\"input_tokens\":0,\"output_tokens\":0}",
            "{\"input_tokens\":-1,\"output_tokens\":2}",
            "{\"input_tokens\":1.5,\"output_tokens\":2}",
            "{\"input_tokens\":2147483647,\"output_tokens\":2}",
            "{\"input_tokens\":3,\"input_tokens\":4,\"output_tokens\":2}"))
      variants.add(output(usage));
    variants.add(
        output("{\"input_tokens\":3,\"output_tokens\":2}")
            + "\n{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
    variants.add("{\"type\":\"turn.started\"}\n{\"type\":\"turn.failed\"}");
    for (String text : variants) {
      var budget = new OutputLimitRunBudget(0, 10, null, 100);
      var paid = new AtomicInteger();
      assertTrue(
          assertThrows(
                  RuntimeException.class,
                  () -> service(executor(text, paid)).review(run(budget), "review", null, ""))
              .getMessage()
              .contains("TOKEN_USAGE_UNKNOWN"));
      assertTrue(budget.usageUnknown());
      assertEquals(1, paid.get());
    }
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void truncatedAndFailedProcessCannotProvideTrustedUsage() throws Exception {
    for (var output :
        List.of(
            new ExternalAgentProcessRunner.Output(
                ExternalAgentResult.Status.SUCCESS,
                output("{\"input_tokens\":3,\"output_tokens\":2}"),
                "",
                0,
                1,
                true),
            new ExternalAgentProcessRunner.Output(
                ExternalAgentResult.Status.TOTAL_TIMEOUT, "", "", null, 1, false))) {
      var budget = new OutputLimitRunBudget(0, 10, null, 100);
      var paid = new AtomicInteger();
      assertTrue(
          assertThrows(
                  RuntimeException.class,
                  () -> service(executor(output, paid)).review(run(budget), "review", null, ""))
              .getMessage()
              .contains("TOKEN_USAGE_UNKNOWN"));
      assertTrue(budget.usageUnknown());
    }
  }

  /** 外部エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void canceledProcessKeepsCancellationSignal() {
    var paid = new AtomicInteger();
    var run = run(new OutputLimitRunBudget(0, 10, null, 100));
    var output =
        new ExternalAgentProcessRunner.Output(
            ExternalAgentResult.Status.CANCELLED, "", "", null, 1, false);
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> service(executor(output, paid)).review(run, "review", null, ""));
    assertTrue(run.isCancelled());
    assertEquals(1, paid.get());
  }

  /** 軽量CLI使用状況について、目標保存と検索の窓口再開の後も対象の情報が残ることを確認する。 */
  @Test
  void cliUsageSurvivesGoalRepositoryRestartAndStoppedReviewHasTerminalAudit() throws Exception {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("goal.db"));
    var props = new dev.mikoto2000.rei.llm.LlmProperties();
    props.getOutputLimit().setMaxTotalTokensPerGoal(4);
    var goals = new dev.mikoto2000.rei.goal.GoalRepository(source, Clock.systemUTC(), props);
    var goal =
        goals.create(
            new AgentRunContext("source", "chat", root, "p"),
            "Artifact",
            "out.txt",
            "a".repeat(64),
            3,
            10);
    var claim = goals.claim("p", goal.id());
    var id = goals.beginAttempt(claim);
    var budget = new OutputLimitRunBudget(0, 10, goals.modelBudget(claim, id));
    var run = run(budget);
    run.setRunContext(new AgentRunContext(id, "chat", root, "p"));
    var paid = new AtomicInteger();
    var service = service(executor(output("{\"input_tokens\":3,\"output_tokens\":2}"), paid));
    var history = new ExternalReviewRepository(source, Clock.systemUTC());
    service.reviewHistory(history);
    assertTrue(
        assertThrows(RuntimeException.class, () -> service.review(run, "review", null, ""))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals("FAILED", history.list("p").getFirst().status());
    var restarted = new dev.mikoto2000.rei.goal.GoalRepository(source, Clock.systemUTC());
    assertEquals(5, restarted.get("p", goal.id()).totalTokens());
    assertEquals(1, restarted.get("p", goal.id()).llmCallsUsed());
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void disabledIntegrationPreservesLegacyCliAndCapabilityFailureSpendsNothing() {
    var paid = new AtomicInteger();
    var budget = new OutputLimitRunBudget(0, 10, null, 5);
    var service = service(executor("unstructured", paid));
    service.modelBudgetProperties(new CodexProperties());
    assertTrue(service.review(run(budget), "review", null, "").success());
    assertEquals(0, budget.totalTokens());
    assertFalse(budget.usageUnknown());
    var runner =
        new ExternalAgentProcessRunner() {
          /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
          public Output run(
              List<String> command,
              Path cwd,
              String input,
              Duration total,
              Duration idle,
              int limit,
              BooleanSupplier cancelled) {
            assertTrue(command.contains("--help"));
            return new Output(ExternalAgentResult.Status.SUCCESS, "old CLI", "", 0, 1, false);
          }
        };
    assertEquals(
        ExternalAgentResult.Status.UNAVAILABLE,
        service(new CodexExternalAgentExecutor(new CodexProperties(), runner))
            .review(run(budget), "review", null, "")
            .status());
    assertEquals(10, budget.remainingLlmCalls());
  }

  /** 外部エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void overBudgetFixProposalCannotReachChangeSetService() throws Exception {
    java.nio.file.Files.writeString(root.resolve("note.txt"), "before");
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("reviews.db"));
    var history = new ExternalReviewRepository(source, Clock.systemUTC());
    history.start(
        new AgentRunContext("parent", "chat", root, "p"),
        "previous",
        root.toRealPath(),
        "note.txt",
        null);
    history.finish(
        "p",
        "previous",
        new ExternalAgentResult(
            ExternalAgentResult.Status.SUCCESS, "reviewed", List.of(), List.of(), 1, 0, ""));
    String proposal =
        "{\"summary\":\"fix\",\"findings\":[],\"warnings\":[],\"proposal\":{\"path\":\"note.txt\",\"expectedText\":\"before\",\"replacement\":\"after\"}}";
    var service =
        service(
            executor(
                output("{\"input_tokens\":3,\"output_tokens\":2}", proposal), new AtomicInteger()));
    service.reviewHistory(history);
    var changes = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.TextChangeSetService.class);
    service.changeSets(changes);
    var run = run(new OutputLimitRunBudget(0, 10, null, 4));
    run.setUserRequest("Codex に修正案を提案して");
    assertTrue(
        assertThrows(RuntimeException.class, () -> service.proposeFix(run, "previous", "fix", ""))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
    org.mockito.Mockito.verifyNoInteractions(changes);
    assertEquals("before", java.nio.file.Files.readString(root.resolve("note.txt")));
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void propertyBindingAndUnsupportedTokenAccountingNeverStartLegacyExecutor() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            Map.of("rei.external-agents.codex.inherit-run-model-budget", "true"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.external-agents.codex",
                org.springframework.boot.context.properties.bind.Bindable.of(CodexProperties.class))
            .get();
    assertTrue(properties.isInheritRunModelBudget());
    assertFalse(new CodexProperties().isInheritRunModelBudget());
    var invoked = new AtomicInteger();
    ExternalAgentExecutor legacy =
        (request, cancelled) -> {
          invoked.incrementAndGet();
          return ExternalAgentResult.rejected("legacy");
        };
    assertTrue(
        assertThrows(
                RuntimeException.class,
                () ->
                    service(legacy)
                        .review(run(new OutputLimitRunBudget(0, 10, null, 10)), "review", null, ""))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0, invoked.get());
  }
}
