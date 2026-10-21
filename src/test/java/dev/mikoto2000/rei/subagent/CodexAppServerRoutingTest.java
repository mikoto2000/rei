package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Codexアプリケーションサーバー振り分けの結果と状態の契約を検証するテスト。 */
class CodexAppServerRoutingTest {
  /** 所有者予算を保持することを確認する。 */
  @Test
  void explicitBackendRoutesBothEntrypointsAndRetainsOwnerBudget() {
    var runner = mock(SubAgentRunner.class, CALLS_REAL_METHODS);
    var external = mock(CodexAppServerSubAgentBackend.class);
    ObjectProvider<CodexAppServerSubAgentBackend> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(external);
    runner.configureAppServerBackend(provider);
    var owner = new AgentRunContext("run", "session", Path.of(".").toAbsolutePath(), "project");
    var budget = mock(LlmCallReservation.class);
    var request =
        new ChatGptSubAgentBackend.Request(
            "reviewer", "codex-app-server", "model", "account", "task", List.of(), "{}");
    runner.runManualBackend(request, owner, false, budget);
    runner.runAutomaticBackend(request, owner, budget);
    verify(external).run(request, owner, false, budget);
    verify(external).runAutomatic(request, owner, budget);
  }

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unavailableAppServerNeverFallsBackToCliOrHttpPlan() {
    var runner = mock(SubAgentRunner.class, CALLS_REAL_METHODS);
    ObjectProvider<ChatGptSubAgentBackend> plan = mock(ObjectProvider.class);
    runner.configurePlanBackend(plan);
    var request =
        new ChatGptSubAgentBackend.Request(
            "reviewer", "codex-app-server", "model", "account", "task", List.of(), "{}");
    assertThrows(
        IllegalArgumentException.class, () -> runner.runManualBackend(request, null, true, null));
    assertThrows(
        IllegalArgumentException.class, () -> runner.runAutomaticBackend(request, null, null));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(plan);
  }

  /** 補助エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void parentToolAdvertisesAppServerDestination() {
    var tools = new SubAgentTools(mock(SubAgentRunner.class), mock(SubAgentRegistry.class));
    assertTrue(tools.callback().getToolDefinition().description().contains("codex-app-server"));
  }
}
