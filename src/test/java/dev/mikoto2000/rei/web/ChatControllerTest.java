package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ChatControllerTest {
  @TempDir Path directory;
  @Test void acceptedResponseHasLocationAndInvalidRequestsHaveExplicitStatuses() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var clock = Clock.systemUTC();
    var service = new ChatSubmitService(projects, new SessionRegistry(clock), new RunRegistry(clock), (c, p) -> {});
    var mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();
    var result = mvc.perform(post("/api/v1/chat").contentType("application/json")
        .content("{\"message\":\"hello\",\"projectId\":\"" + project.id() + "\"}"))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.sessionId").isString()).andReturn();
    var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
    assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/runs/" + json.get("runId").asText());
    assertThat(json.get("turnId")).isEqualTo(json.get("runId"));
    mvc.perform(post("/api/v1/chat").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/chat").contentType("application/json")
        .content("{\"message\":\"hello\",\"projectId\":\"unknown\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/chat").contentType("application/json")
        .content("{\"message\":\"hello\",\"projectId\":\"" + project.id() + "\",\"sessionId\":\"unknown\"}"))
        .andExpect(status().isNotFound());
  }
}
