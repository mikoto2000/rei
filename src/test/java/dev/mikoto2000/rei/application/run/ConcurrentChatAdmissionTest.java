package dev.mikoto2000.rei.application.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class ConcurrentChatAdmissionTest {
  @TempDir Path root;

  @Test
  void explicitConversationSharesSessionButStartsAnIndependentRunDuringExclusiveTask() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var clock = Clock.systemUTC();
    var registry = new RunRegistry(clock);
    var jobs = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (c, p, q) -> {});
    var service =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            registry,
            new SessionLifecycle(new FileSessionRepository(root.resolve("sessions.json")), clock),
            (c, p) -> router.submit(c, p),
            true);
    var task = service.submit("long task", project.id(), null);
    var question =
        service.submit(
            "independent question",
            project.id(),
            task.conversationId(),
            AgentRunContext.Mode.CONVERSATION);
    assertThat(jobs).hasSize(2);
    assertThat(question.conversationId()).isEqualTo(task.conversationId());
    assertThat(question.runId()).isNotEqualTo(task.runId());
    assertThat(question.mode()).isEqualTo(AgentRunContext.Mode.CONVERSATION);
    assertThat(registry.runIds()).containsExactlyInAnyOrder(task.runId(), question.runId());
  }

  @Test
  void parallelModesRequireAdministratorOptIn() {
    var projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var clock = Clock.systemUTC();
    var registry = new RunRegistry(clock);
    var service =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            registry,
            new FileSessionRepository(root.resolve("sessions.json")),
            clock,
            (c, p) -> {});
    assertThatThrownBy(
            () -> service.submit("question", project.id(), null, AgentRunContext.Mode.CONVERSATION))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.runIds()).isEmpty();
  }
}
