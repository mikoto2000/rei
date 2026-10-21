package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Claudeコード拡張子サービスの結果と状態の契約を検証するテスト。 */
class ClaudeCodeExtensionServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う履歴。 */
  ExternalReviewRepository history;

  /** 検証に使う取得元。 */
  org.springframework.jdbc.datasource.DriverManagerDataSource source;

  /** 検証に使うOSの機能識別子。 */
  String nativeId = UUID.randomUUID().toString();

  /** 検証に使う所属プロジェクトの識別子。 */
  String projectId = UUID.randomUUID().toString();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    Files.writeString(root.resolve("A.txt"), "before\n");
    source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            "jdbc:sqlite:" + root.resolve("history.db"));
    history = new ExternalReviewRepository(source, Clock.systemUTC());
  }

  /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
  RunExecutionContext run(String id, String input) {
    var run =
        new RunExecutionContext(
            id, new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0, 10), null, null, null);
    run.setRunContext(new AgentRunContext(id, "session", root, projectId));
    run.setUserRequest(input);
    return run;
  }

  /** {@code parent} の条件を再現するため、検証用の操作や状態を扱う。 */
  void parent() {
    var owner = new AgentRunContext("initial", "session", root, projectId);
    history.start(owner, "parent", root, "A.txt", null, "claude");
    history.finish(
        projectId,
        "parent",
        new ExternalAgentResult(
            ExternalAgentResult.Status.SUCCESS,
            "ok",
            List.of(),
            List.of(),
            0,
            0,
            "",
            "parent",
            nativeId));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  ExternalAgentDelegationService service(ExternalAgentExecutor executor) {
    var service =
        new ExternalAgentDelegationService(
            executor,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),
            e -> {},
            Optional.empty());
    service.reviewHistory(history);
    return service;
  }

  /** 継続入力について、提供元を維持することを確認する。 */
  @Test
  void continuationKeepsProviderAndConsumesItsParentEvenAfterFailure() {
    parent();
    var calls = new AtomicInteger();
    var executor =
        new ExternalAgentExecutor() {
          /** 検証用に、外部エージェントへの委譲において、継続入力への対応可否を返す。 */
          public boolean supportsContinuation(ExternalAgentRequest.Agent agent) {
            return agent == ExternalAgentRequest.Agent.CLAUDE;
          }

          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r, java.util.function.BooleanSupplier c) {
            fail("Budget required");
            return null;
          }

          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r,
              java.util.function.BooleanSupplier c,
              dev.mikoto2000.rei.llm.ModelCallBudget budget) {
            assertEquals(ExternalAgentRequest.Agent.CLAUDE, r.agent());
            assertEquals(nativeId, r.externalSessionId());
            budget.run();
            calls.incrementAndGet();
            return new ExternalAgentResult(
                ExternalAgentResult.Status.FAILED, "unavailable", List.of(), List.of(), 0, 1, "");
          }
        };
    try (var service = service(executor)) {
      var plain = run("plain", "Claude Code にレビューして");
      assertEquals(
          ExternalAgentResult.Status.REJECTED,
          service
              .continueReview(plain, ExternalAgentRequest.Agent.CLAUDE, "parent", "review", null)
              .status());
      assertFalse(plain.externalDelegationUsed());
      var first =
          service.continueReview(
              run("continue", "Claude Code に続きのレビューを依頼して"),
              ExternalAgentRequest.Agent.CLAUDE,
              "parent",
              "review",
              null);
      assertEquals(ExternalAgentResult.Status.FAILED, first.status());
      assertTrue(history.continuationAttempted("parent"));
      assertEquals(
          ExternalAgentResult.Status.REJECTED,
          service
              .continueReview(
                  run("retry", "Claude Code に続きのレビューを依頼して"),
                  ExternalAgentRequest.Agent.CLAUDE,
                  "parent",
                  "review",
                  null)
              .status());
      assertEquals(1, calls.get());
      assertEquals("claude", history.get(projectId, first.reviewId()).agent());
    }
  }

  /** 外部エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void fixProposalSavesAChangeSetWithoutApplyingAndDoesNotAuthorizeCodex() {
    parent();
    var calls = new AtomicInteger();
    var executor =
        new ExternalAgentExecutor() {
          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r, java.util.function.BooleanSupplier c) {
            throw new AssertionError();
          }

          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r,
              java.util.function.BooleanSupplier c,
              dev.mikoto2000.rei.llm.ModelCallBudget budget) {
            assertEquals(ExternalAgentRequest.Agent.CLAUDE, r.agent());
            assertEquals(ExternalAgentRequest.Action.PROPOSE_FIX, r.action());
            budget.run();
            calls.incrementAndGet();
            return new ExternalAgentResult(
                ExternalAgentResult.Status.SUCCESS,
                "proposal",
                List.of(),
                List.of(),
                0,
                0,
                "",
                null,
                null,
                new dev.mikoto2000.rei.core.TextChangeSetService.Request(
                    "A.txt", "before\n", "after\n"),
                null);
          }
        };
    try (var service = service(executor)) {
      service.changeSets(
          new dev.mikoto2000.rei.core.TextChangeSetService(
              new dev.mikoto2000.rei.core.TextChangeSetRepository(source)));
      var run = run("fix", "Claude Code に修正案を依頼して");
      var result =
          service.proposeFix(run, ExternalAgentRequest.Agent.CLAUDE, "parent", "fix", null);
      assertTrue(result.success(), result.summary());
      assertNotNull(result.changeSetId());
      assertNull(result.proposedChange());
      assertEquals(1, calls.get());
      try {
        assertEquals("before\n", Files.readString(root.resolve("A.txt")));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      assertFalse(ExternalAgentAuthorization.explicitFixProposalRequest(run.userRequest()));
    }
  }

  /** 外部エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void parallelClaudeUsesTheCommonBoundedBatchAndOneParentDelegation() {
    var calls = new AtomicInteger();
    var executor =
        new ExternalAgentExecutor() {
          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r, java.util.function.BooleanSupplier c) {
            throw new AssertionError();
          }

          /** 検証用に、要求された処理を実行する。 */
          public ExternalAgentResult execute(
              ExternalAgentRequest r,
              java.util.function.BooleanSupplier c,
              dev.mikoto2000.rei.llm.ModelCallBudget budget) {
            assertEquals(ExternalAgentRequest.Agent.CLAUDE, r.agent());
            budget.run();
            calls.incrementAndGet();
            return new ExternalAgentResult(
                ExternalAgentResult.Status.SUCCESS, "ok", List.of(), List.of(), 0, 0, "");
          }
        };
    try (var service = service(executor)) {
      var properties = new ClaudeCodeProperties();
      properties.setEnabled(true);
      properties.setParallelReviewEnabled(true);
      service.claudeProperties(properties);
      var requests =
          List.of(
              new ExternalAgentDelegationService.ParallelRequest("one", "review", "A.txt", ""),
              new ExternalAgentDelegationService.ParallelRequest("two", "review", "A.txt", ""));
      var wrong = run("wrong", "Codex に並列レビューして");
      assertEquals(
          ExternalAgentDelegationService.ParallelStatus.REJECTED,
          service.reviewParallel(wrong, ExternalAgentRequest.Agent.CLAUDE, requests).status());
      assertFalse(wrong.externalDelegationUsed());
      var parent = run("batch", "Claude Code に並列レビューして");
      var result = service.reviewParallel(parent, ExternalAgentRequest.Agent.CLAUDE, requests);
      assertEquals(ExternalAgentDelegationService.ParallelStatus.COMPLETED, result.status());
      assertEquals(2, result.items().size());
      assertEquals(2, calls.get());
      assertTrue(parent.externalDelegationUsed());
      assertEquals(2, history.list(projectId, "claude").size());
      assertEquals(
          ExternalAgentResult.Status.REJECTED,
          service
              .review(parent, ExternalAgentRequest.Agent.CLAUDE, "again", "A.txt", null)
              .status());
      assertEquals(2, calls.get());
    }
  }
}
