package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunControllerTest {
  @Test void exposesStableMetadataAndUnknownRunIs404() throws Exception {
    var registry = new RunRegistry(Clock.systemUTC());
    registry.register(RunRegistryTest.context("run"));
    var mvc = MockMvcBuilders.standaloneSetup(new RunController(new RunService(registry)))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/v1/runs/run")).andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value("run")).andExpect(jsonPath("$.turnId").value("run"))
        .andExpect(jsonPath("$.sessionId").value("session")).andExpect(jsonPath("$.projectId").value("project"))
        .andExpect(jsonPath("$.status").value("QUEUED")).andExpect(jsonPath("$.startedAt").isEmpty())
        .andExpect(jsonPath("$.completedAt").isEmpty()).andExpect(jsonPath("$.failure").isEmpty())
        .andExpect(jsonPath("$.context").doesNotExist());
    registry.transition("run", RunStatus.RUNNING, null);
    registry.transition("run", RunStatus.FAILED, new RunFailure("error", "failed"));
    mvc.perform(get("/api/v1/runs/run")).andExpect(jsonPath("$.failure.type").value("error"))
        .andExpect(jsonPath("$.startedAt").isString()).andExpect(jsonPath("$.completedAt").isString());
    mvc.perform(get("/api/v1/runs/unknown")).andExpect(status().isNotFound());
  }
}
