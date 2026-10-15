package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanParallelReviewTest {
  @Test
  void mixedReviewPreservesSuccessWhenExternalChildFails() {
    var runner = mock(SubAgentRunner.class);
    var registry = mock(SubAgentRegistry.class);
    var definition =
        new SubAgentDefinition(
            "reviewer",
            "Review",
            "review",
            "prompt",
            List.of(),
            null,
            3,
            Duration.ofSeconds(10),
            java.nio.file.Path.of("reviewer.yaml"));
    when(registry.findById("reviewer")).thenReturn(Optional.of(definition));
    var owner = new AgentRunContext("parent", "session", java.nio.file.Path.of("."), "project");
    var budget = mock(LlmCallReservation.class);
    var local =
        new SubAgentResult(
            "reviewer",
            "local",
            SubAgentResult.Status.COMPLETED,
            "ok",
            Instant.now(),
            Instant.now());
    when(runner.run(eq("reviewer"), eq("same task"), isNull(), eq(budget))).thenReturn(local);
    when(runner.runAutomaticBackend(any(), eq(owner), eq(budget)))
        .thenThrow(new IllegalArgumentException("unavailable"));
    try (var parallel =
            new ParallelSubAgentDelegator(runner, registry, new CommandCancellationService());
        var scope = AgentRunScope.open(owner)) {
      var batch =
          parallel.delegate(
              List.of(
                  new ParallelSubAgentDelegator.Request("local", "reviewer", "same task", null),
                  new ParallelSubAgentDelegator.Request(
                      "plan",
                      "reviewer",
                      "same task",
                      null,
                      "chatgpt-plan",
                      "personal",
                      "model",
                      List.of(),
                      "{\"unverified\":[]}")),
              budget);
      assertEquals(ParallelSubAgentDelegator.Status.PARTIAL, batch.status());
      assertEquals(local, batch.items().get(0).result());
      assertEquals(ParallelSubAgentDelegator.Status.FAILED, batch.items().get(1).status());
      assertNotNull(batch.comparison());
      assertFalse(batch.comparison().truthVerified());
    }
  }
}
