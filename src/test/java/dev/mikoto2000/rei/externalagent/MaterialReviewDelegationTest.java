package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 教材レビュー委譲の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MaterialReviewDelegationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証用に、外部エージェントへの委譲の操作を実行する。 */
  RunExecutionContext run(String prompt) {
    var run =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(2, 10),
            new ProgressEvaluator(root, null),
            new AgentEventFactory(Clock.systemUTC()),
            e -> {});
    run.setRunContext(new AgentRunContext("run", "conversation", root, "project"));
    run.setUserRequest(prompt);
    return run;
  }

  /** 外部エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void selectsDistinctTaskAndSharesEventsAndOneShotBudget() throws Exception {
    var requests = new ArrayList<ExternalAgentRequest>();
    var events = new ArrayList<AgentEvent>();
    try (var service =
        service(
            (request, cancelled) -> {
              requests.add(request);
              return new ExternalAgentResult(
                  ExternalAgentResult.Status.SUCCESS, "ok", List.of(), List.of(), 1, 0, "");
            },
            events)) {
      var run = run("/agent codex material-review");
      assertTrue(service.review(run, "material", null, "").success());
      assertEquals("MATERIAL_REVIEW", requests.getFirst().action().name());
      assertNull(requests.getFirst().target()); // Same omission resolution as generic review.
      assertEquals(
          List.of(AgentEventType.DELEGATION_STARTED, AgentEventType.DELEGATION_COMPLETED),
          events.stream().map(AgentEvent::type).toList());
      assertEquals(
          ExternalAgentResult.Status.REJECTED, service.review(run, "again", null, "").status());
      assertEquals(1, requests.size());
      var docs = Files.createDirectory(root.resolve("docs"));
      assertTrue(
          service
              .review(run("/agent codex material-review docs"), "material", "docs", "")
              .success());
      assertEquals(docs.toRealPath(), requests.getLast().target());
      assertTrue(service.review(run("/agent codex review"), "generic", null, "").success());
      assertEquals(ExternalAgentRequest.Action.REVIEW, requests.getLast().action());
    }
  }

  /** 外部エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingTargetIsRejectedBeforeExternalExecution() {
    try (var service =
        service(
            (r, c) -> {
              fail("Must not start Codex");
              return null;
            },
            new ArrayList<>())) {
      var result =
          service.review(run("/agent codex material-review missing"), "material", "missing", "");
      assertEquals(ExternalAgentResult.Status.REJECTED, result.status());
      assertTrue(result.summary().contains("does not exist"));
    }
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  ExternalAgentDelegationService service(ExternalAgentExecutor executor, List<AgentEvent> events) {
    return new ExternalAgentDelegationService(
        executor,
        new CommandCancellationService(),
        new AgentEventFactory(Clock.systemUTC()),
        events::add,
        Optional.empty());
  }
}
