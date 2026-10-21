package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Slackエージェントツール一覧の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackAgentToolsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  SlackAgentProperties settings() {
    var p = new SlackAgentProperties();
    p.setEnabled(true);
    p.setAllowedWorkspaceIds(Set.of("T1"));
    p.setAllowedChannelIds(Set.of("C1"));
    p.setAgents(Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "")));
    return p;
  }

  /** {@code execution} の条件を再現するための補助情報を返す。 */
  RunExecutionContext execution(int calls) {
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, calls),
            new ProgressEvaluator(dir, null),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    e.setRunContext(new AgentRunContext("run", "session", dir, "project"));
    return e;
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  ToolContext context(RunExecutionContext e) {
    return new ToolContext(Map.of(RunExecutionContext.KEY, e, "toolCallId", "call"));
  }

  /** {@code repo} の条件を再現するための補助情報を返す。 */
  SlackAgentRepository repo() {
    return new SlackAgentRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db")), Clock.systemUTC());
  }

  /** Slackとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void policyAuthorizesResolvedDestinationAndExplicitTextBeforeCreation() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    var t = new SlackAgentTools(settings(), r, guard);
    var result =
        t.askExternalAgent("tenten", "question", "expected", 300, null, context(execution(3)));
    assertEquals("CREATED", result.status());
    assertFalse(result.requestId().isBlank());
    verify(guard).check(eq("askExternalAgent"), contains("C1"), any());
    assertEquals(1, r.forRun("run").size());
  }

  /** Slackとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void deniedPermissionHasNoRequestOrSend() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    doThrow(new ToolPermissionException("askExternalAgent", PermissionDecision.DENY))
        .when(guard)
        .check(eq("askExternalAgent"), anyString(), any());
    var t = new SlackAgentTools(settings(), r, guard);
    assertThrows(
        ToolPermissionException.class,
        () -> t.askExternalAgent("tenten", "q", "", 300, null, context(execution(2))));
    assertTrue(r.forRun("run").isEmpty());
  }

  /** Slackとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void exhaustedRunAndInvalidDestinationCannotCreateRequest() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    var t = new SlackAgentTools(settings(), r, guard);
    assertThrows(
        IllegalStateException.class,
        () -> t.askExternalAgent("tenten", "q", "", 300, null, context(execution(0))));
    var p = settings();
    p.setAllowedChannelIds(Set.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SlackAgentTools(p, r, guard)
                .askExternalAgent("tenten", "q", "", 300, null, context(execution(2))));
    assertTrue(r.forRun("run").isEmpty());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(guard);
  }

  /** Slackとの連携で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void secretPatternsAreRemovedAndNoSessionHistoryIsAttached() {
    var r = repo();
    var t = new SlackAgentTools(settings(), r, mock(ToolPermissionGuard.class));
    var e = execution(3);
    e.setConversationSnapshot(
        List.of(new org.springframework.ai.chat.messages.UserMessage("PRIVATE SESSION HISTORY")));
    var result =
        t.askExternalAgent(
            "tenten",
            "token " + String.join("-", "xoxb", "123456789", "123456789", "AbCdEfGhIjKlMn"),
            "",
            300,
            null,
            context(e));
    var request = r.get(result.requestId());
    assertFalse(request.question().contains("xoxb-"));
    assertFalse(request.question().contains("PRIVATE SESSION HISTORY"));
  }

  /** Slackとの連携の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void networkWriteCapabilityCannotBeRelabeledAsRead() {
    assertEquals(
        Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        ToolPermissionPolicy.intrinsicCapabilities("askExternalAgent"));
  }
}
