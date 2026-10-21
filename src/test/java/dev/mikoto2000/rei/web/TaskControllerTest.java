package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.application.task.TaskControlService;
import dev.mikoto2000.rei.application.task.TaskManagerService;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** タスク受付窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class TaskControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 利用既存の実行境界条件を書き込むことを確認する。 */
  @Test
  void listSubmitExactOwnerControlsStaleTargetsAndStrictWritesUseExistingRunBoundary()
      throws Exception {
    var clock = Clock.systemUTC();
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var registry = new RunRegistry(clock);
    var jobs = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (c, p, q) -> {});
    var lifecycle =
        new RunService(
            registry,
            new InMemoryAgentEventBus(),
            new AgentEventFactory(clock),
            new CommandCancellationService(),
            router::cancelQueued);
    var chats =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            registry,
            new FileSessionRepository(root.resolve("sessions.json")),
            clock,
            (owner, prompt) -> router.submit(owner, prompt));
    var owner = new AgentRunContext("one", "session", root, project.id());
    registry.register(owner);
    router.submit(owner, "work");
    var tasks = new TaskManagerService(projects, registry, null, null, null, null);
    var controls =
        new TaskControlService(tasks, chats, lifecycle, registry, router, null, null, null, null);
    var mvc =
        MockMvcBuilders.standaloneSetup(new TaskController(tasks, controls))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(get("/api/v1/tasks").param("limit", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value("run:one"));
    mvc.perform(
            get("/api/v1/tasks/run:one")
                .param("projectId", project.id())
                .param("sessionId", "foreign"))
        .andExpect(status().isNotFound());
    String body =
        "{\"projectId\":\""
            + project.id()
            + "\",\"sessionId\":\"session\",\"expectedRunId\":\"previous\",\"expectedRevision\":0}";
    mvc.perform(post("/api/v1/tasks/run:one/cancel").contentType("application/json").content(body))
        .andExpect(status().isConflict());
    assertThat(registry.get("one").status()).isEqualTo(RunStatus.QUEUED);
    mvc.perform(
            post("/api/v1/tasks/run:one/cancel")
                .contentType("application/json")
                .content(body.replace("previous", "one").replace("}", ",\"root\":\"C:/other\"}")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/tasks/run:one/input")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\""
                        + project.id()
                        + "\",\"sessionId\":\"session\",\"expectedRunId\":\"one\",\"message\":\"guidance\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/tasks")
                .contentType("application/json")
                .content(
                    "{\"projectId\":\""
                        + project.id()
                        + "\",\"message\":\"question\",\"mode\":\"CONVERSATION\"}"))
        .andExpect(status().isBadRequest());
    assertThat(registry.runIds()).containsExactly("one");
    mvc.perform(
            post("/api/v1/tasks")
                .contentType("application/json")
                .content("{\"projectId\":\"" + project.id() + "\",\"message\":\"new task\"}"))
        .andExpect(status().isAccepted())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.mode").value("EXCLUSIVE"));
    assertThat(registry.runIds()).hasSize(2);
  }
}
