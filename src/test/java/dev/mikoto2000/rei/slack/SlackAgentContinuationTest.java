package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Slackエージェント継続入力の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackAgentContinuationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  SlackAgentContinuationStore store() {
    return new SlackAgentContinuationStore(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db")));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", dir, "project");
  }

  /** {@code messages} の条件を再現するための補助情報を返す。 */
  List<Message> messages() {
    return List.of(
        new SystemMessage("never persist system"),
        new UserMessage("original request"),
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall("call", "function", "askExternalAgent", "{}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse("call", "askExternalAgent", "WAITING")))
            .build());
  }

  /** 再開について、同一所有者を復元することを確認する。 */
  @Test
  void restartRestoresSameOwnerAndStructuredToolHistoryButNeverSystemMessages() {
    var s = store();
    s.save(
        owner(),
        "original request",
        messages(),
        new OutputLimitRunBudget.Snapshot(1, 2, 100, false));
    var restored = store().get(owner());
    assertEquals(2, restored.budget().llmCalls());
    assertEquals(100, restored.budget().totalTokens());
    assertEquals(3, restored.messages().size());
    assertInstanceOf(ToolResponseMessage.class, restored.messages().getLast());
    assertThrows(
        IllegalArgumentException.class,
        () -> s.get(new AgentRunContext("run", "other", dir, "project")));
  }

  /** Slackとの連携で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void durableResumeClaimIsOneShotAndOwnerLossNeverReplays() {
    var s = store();
    s.save(owner(), "request", messages(), new OutputLimitRunBudget.Snapshot(0, 1, 0, false));
    assertTrue(s.claim("run"));
    assertFalse(store().claim("run"));
    store().recover();
    assertEquals("UNKNOWN", s.state("run"));
    assertFalse(s.claim("run"));
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationMakesSavedContinuationIneligible() {
    var s = store();
    s.save(owner(), "request", messages(), new OutputLimitRunBudget.Snapshot(0, 1, 0, false));
    s.cancel("run");
    assertFalse(s.claim("run"));
    assertEquals("CANCELLED", s.state("run"));
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void budgetRestoreNeverReplenishesCallsTokensOrReplans() {
    var b = new OutputLimitRunBudget(2, 3, null, 100);
    b.restore(new OutputLimitRunBudget.Snapshot(2, 2, 90, false));
    assertEquals(1, b.remainingLlmCalls());
    assertFalse(b.tryConsumeReplan());
    assertTrue(b.tryConsumeLlmCall());
    assertFalse(b.tryConsumeLlmCall());
    b.recordTotalTokens(10);
    assertTrue(b.tokenExhausted());
  }

  /** 登録台帳待機中について、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void registryWaitingSurvivesRestartAndSameRunCanResume() {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("runs.db"));
    var r = new RunRegistry(Clock.systemUTC(), source);
    r.register(owner());
    r.transition("run", RunStatus.RUNNING, null);
    r.transition("run", RunStatus.WAITING, null);
    r.close();
    var restored = new RunRegistry(Clock.systemUTC(), source);
    assertEquals(RunStatus.WAITING, restored.get("run").status());
    assertTrue(restored.claimWaiting(owner()));
    assertTrue(restored.transition("run", RunStatus.RUNNING, null));
    assertFalse(restored.claimWaiting(owner()));
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void restoredWaitingRunCanBeCancelledBeforeItsReplyArrives() {
    var clock = Clock.systemUTC();
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("runs.db"));
    var first = new RunRegistry(clock, source);
    first.register(owner());
    first.transition("run", RunStatus.RUNNING, null);
    first.transition("run", RunStatus.WAITING, null);
    first.close();
    var restored = new RunRegistry(clock, source);
    try (var runs =
        new RunService(
            restored,
            new dev.mikoto2000.rei.event.InMemoryAgentEventBus(),
            new dev.mikoto2000.rei.event.AgentEventFactory(clock),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            id -> false)) {
      assertTrue(runs.cancel("run").accepted());
      assertEquals(RunStatus.CANCELLED, restored.get("run").status());
      assertFalse(runs.cancel("run").accepted());
    }
  }
}
