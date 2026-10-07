package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("integration")
class MaterialReviewDelegationTest {
  @TempDir Path root;
  RunExecutionContext run(String prompt) {
    var run = new RunExecutionContext("run", new OutputLimitRunBudget(2, 10), new ProgressEvaluator(root, null),
        new AgentEventFactory(Clock.systemUTC()), e -> {});
    run.setRunContext(new AgentRunContext("run", "conversation", root, "project"));
    run.setUserRequest(prompt);
    return run;
  }
  @Test void selectsDistinctTaskAndSharesEventsAndOneShotBudget() throws Exception {
    var requests = new ArrayList<ExternalAgentRequest>();
    var events = new ArrayList<AgentEvent>();
    try (var service = service((request, cancelled) -> {
      requests.add(request);
      return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS, "ok", List.of(), List.of(), 1, 0, "");
    }, events)) {
      var run = run("/agent codex material-review");
      assertTrue(service.review(run, "material", null, "").success());
      assertEquals("MATERIAL_REVIEW", requests.getFirst().action().name());
      assertNull(requests.getFirst().target()); // Same omission resolution as generic review.
      assertEquals(List.of(AgentEventType.DELEGATION_STARTED, AgentEventType.DELEGATION_COMPLETED), events.stream().map(AgentEvent::type).toList());
      assertEquals(ExternalAgentResult.Status.REJECTED, service.review(run, "again", null, "").status());
      assertEquals(1, requests.size());
      var docs = Files.createDirectory(root.resolve("docs"));
      assertTrue(service.review(run("/agent codex material-review docs"), "material", "docs", "").success());
      assertEquals(docs.toRealPath(), requests.getLast().target());
      assertTrue(service.review(run("/agent codex review"), "generic", null, "").success());
      assertEquals(ExternalAgentRequest.Action.REVIEW, requests.getLast().action());
    }
  }
  @Test void missingTargetIsRejectedBeforeExternalExecution() {
    try (var service = service((r, c) -> { fail("Must not start Codex"); return null; }, new ArrayList<>())) {
      var result = service.review(run("/agent codex material-review missing"), "material", "missing", "");
      assertEquals(ExternalAgentResult.Status.REJECTED, result.status());
      assertTrue(result.summary().contains("does not exist"));
    }
  }
  ExternalAgentDelegationService service(ExternalAgentExecutor executor, List<AgentEvent> events) {
    return new ExternalAgentDelegationService(executor, new CommandCancellationService(), new AgentEventFactory(Clock.systemUTC()), events::add, Optional.empty());
  }
}
