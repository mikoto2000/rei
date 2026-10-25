package dev.mikoto2000.rei.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.shell.ActiveRunDisplay;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("integration")
class CancellationApiDiagnosticsTest {
  @TempDir Path temp;

  @Test
  void runPollingPreservesStatusFieldsAndAddsCancellationProgress() throws Exception {
    var cancellation = new CommandCancellationService();
    var registry = new RunRegistry(Clock.systemUTC());
    var owner = RunRegistryTest.context("run");
    registry.register(owner);
    registry.transition("run", RunStatus.RUNNING, null);
    cancellation.prepare(owner);
    try (var service =
        new RunService(
            registry,
            new InMemoryAgentEventBus(),
            new AgentEventFactory(Clock.systemUTC()),
            cancellation,
            id -> false)) {
      var mvc = MockMvcBuilders.standaloneSetup(new RunController(service)).build();
      mvc.perform(post("/api/v1/runs/run/cancel"))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.status").value("RUNNING"))
          .andExpect(jsonPath("$.cancellation.status").value("CANCEL_REQUESTED"))
          .andExpect(jsonPath("$.cancellation.requestedAt").isNotEmpty());
      mvc.perform(get("/api/v1/runs/run"))
          .andExpect(jsonPath("$.cancellation.status").value("CANCEL_REQUESTED"));
      mvc.perform(post("/api/v1/runs/run/cancel")).andExpect(status().isOk());
    } finally {
      cancellation.finish("run");
    }
  }

  @Test
  void projectEndpointKeepsAcceptedBooleanAndReportsEachTargetRun() throws Exception {
    var cancellation = new CommandCancellationService();
    var projects = new ProjectRegistry(temp.resolve("projects.json"));
    var project = projects.resolve(temp);
    cancellation.prepare(new AgentRunContext("run", project, "session"));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new ActiveRunController(mock(ActiveRunDisplay.class), cancellation, projects))
            .build();
    mvc.perform(post("/api/v1/projects/" + project.id() + "/runs/cancel"))
        .andExpect(jsonPath("$.accepted").value(true))
        .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"))
        .andExpect(jsonPath("$.runs[0].runId").value("run"));
    mvc.perform(post("/api/v1/projects/" + project.id() + "/runs/cancel"))
        .andExpect(jsonPath("$.accepted").value(false))
        .andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
    cancellation.finish("run");
  }
}
