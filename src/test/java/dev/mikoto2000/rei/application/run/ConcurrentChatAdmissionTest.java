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

/** 並行チャット受付の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ConcurrentChatAdmissionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行の受付と状態管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
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

  /** 実行の受付と状態管理で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
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
