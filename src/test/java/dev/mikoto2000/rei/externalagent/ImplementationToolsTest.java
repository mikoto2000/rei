package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

/** 実装ツール一覧の結果と状態の契約を検証するテスト。 */
class ImplementationToolsTest {
  /** 公開ツール一覧について、取得済み実行を利用することを確認する。 */
  @Test
  void publicToolsUseCapturedRunAndPersistedRequestIdentity() {
    var delegation = mock(ExternalAgentDelegationService.class);
    var requests = mock(ImplementationRequestService.class);
    var tools = new ExternalAgentTools(delegation);
    tools.implementationRequests(requests);
    var run =
        new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run", null, null, null, null);
    var context =
        new ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY, run));
    tools.prepareCodexImplementation(null, null, context);
    verify(requests).prepare(run, null, null, null);
    tools.requestCodexImplementation("id", 1, "hash", context);
    verify(requests).execute(run, "id", 1, "hash");
    var callbacks =
        org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build()
            .getToolCallbacks();
    var names = Arrays.stream(callbacks).map(c -> c.getToolDefinition().name()).toList();
    assertTrue(
        names.containsAll(
            List.of(
                "prepareCodexImplementation",
                "requestCodexImplementation",
                "recordCodexAcceptanceEvaluation")));
    var execution =
        Arrays.stream(callbacks)
            .filter(c -> c.getToolDefinition().name().equals("requestCodexImplementation"))
            .findFirst()
            .orElseThrow();
    assertFalse(
        execution.getToolDefinition().inputSchema().contains("ApprovedImplementationRequest"));
    assertFalse(execution.getToolDefinition().inputSchema().contains("toolContext"));
  }

  /** 外部エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void targetOnlySlashEntryAsksForRequirementsWithoutLaunchingCodex() {
    var executor =
        (ExternalAgentExecutor)
            (request, cancelled) -> {
              fail("No process for target-only command");
              return null;
            };
    try (var service =
        new ExternalAgentDelegationService(
            executor,
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()),
            e -> {},
            Optional.empty())) {
      var props = new CodexProperties();
      props.setImplementationEnabled(true);
      service.modelBudgetProperties(props);
      service.implementations(
          new IsolatedImplementationService(
              java.nio.file.Path.of("unused"),
              new ExternalAgentProcessRunner(),
              (dev.mikoto2000.rei.core.SelfPatchReviewService) null));
      var run =
          new dev.mikoto2000.rei.core.stagnation.RunExecutionContext(
              "run", new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1, 2), null, null, null);
      run.setRunContext(
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              "run", "session", java.nio.file.Path.of("."), "project"));
      run.setUserRequest("/agent codex implement A.txt");
      var result = service.implement(run, "A.txt");
      assertTrue(result.summary().contains("NEEDS_CLARIFICATION"));
      assertFalse(run.externalDelegationUsed());
    }
  }
}
