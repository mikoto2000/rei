package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** チャット受付窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ChatControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 全体実行実行待ちについて、受付を受け付けないことを確認する。 */
  @Test
  void fullRunQueueRejectsAdmissionWithoutLeakingInternalError() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var clock = Clock.systemUTC();
    var registry = new RunRegistry(clock);
    var service =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            registry,
            new dev.mikoto2000.rei.conversation.FileSessionRepository(
                directory.resolve("sessions.json")),
            clock,
            (context, prompt) -> {
              throw new dev.mikoto2000.rei.core.chat.ProjectRunQueue.CapacityExceededException();
            });
    var mvc =
        MockMvcBuilders.standaloneSetup(new ChatController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(
            post("/api/v1/chat")
                .contentType("application/json")
                .content("{\"message\":\"hello\",\"projectId\":\"" + project.id() + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.message").value("Run admission capacity reached"));
    assertThat(registry.runIds()).isEmpty();
  }

  /** Web APIによる操作で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void acceptedResponseHasLocationAndInvalidRequestsHaveExplicitStatuses() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var clock = Clock.systemUTC();
    var service =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            new RunRegistry(clock),
            new dev.mikoto2000.rei.conversation.FileSessionRepository(
                directory.resolve("sessions.json")),
            clock,
            (c, p) -> {});
    var mvc =
        MockMvcBuilders.standaloneSetup(new ChatController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    var result =
        mvc.perform(
                post("/api/v1/chat")
                    .contentType("application/json")
                    .content("{\"message\":\"hello\",\"projectId\":\"" + project.id() + "\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.sessionId").isString())
            .andReturn();
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString());
    assertThat(result.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/runs/" + json.get("runId").asText());
    assertThat(json.get("turnId")).isEqualTo(json.get("runId"));
    mvc.perform(post("/api/v1/chat").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/chat")
                .contentType("application/json")
                .content("{\"message\":\"hello\",\"projectId\":\"unknown\"}"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/chat")
                .contentType("application/json")
                .content(
                    "{\"message\":\"hello\",\"projectId\":\""
                        + project.id()
                        + "\",\"sessionId\":\"unknown\"}"))
        .andExpect(status().isNotFound());
  }
}
