package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class CodexAppServerRoutingTest {
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
    verifyNoInteractions(plan);
  }

  @Test
  void parentToolAdvertisesAppServerDestination() {
    var tools = new SubAgentTools(mock(SubAgentRunner.class), mock(SubAgentRegistry.class));
    assertTrue(tools.callback().getToolDefinition().description().contains("codex-app-server"));
  }
}
