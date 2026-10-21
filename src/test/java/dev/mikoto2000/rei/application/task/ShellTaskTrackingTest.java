package dev.mikoto2000.rei.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunConfiguration;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectClientScope;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** シェルタスクTrackingの結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ShellTaskTrackingTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行中タスクの操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,true"})
  void taskTrackingRegistersShellRunsWithoutEnablingConversationMode(
      boolean tasksEnabled, boolean todayEnabled) {
    var projects = new ProjectService(root, new ProjectRegistry(root.resolve("projects.json")));
    var registry = new RunRegistry(Clock.systemUTC());
    var jobs = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(jobs::add, (c, p, q) -> {});
    var service =
        new RunService(
            registry,
            new InMemoryAgentEventBus(),
            new AgentEventFactory(Clock.systemUTC()),
            new CommandCancellationService(),
            router::cancelQueued);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("runs", registry);
    beans.registerSingleton("service", service);
    var shell =
        new AgentRunConfiguration()
            .shellConversations(
                projects,
                new SessionLifecycle(
                    new FileSessionRepository(root.resolve("sessions.json")), Clock.systemUTC()),
                router,
                false,
                tasksEnabled,
                todayEnabled,
                beans.getBeanProvider(RunRegistry.class),
                beans.getBeanProvider(RunService.class));
    try (var scope = ProjectClientScope.open(projects.newClient())) {
      var run = shell.submit("work");
      assertThat(registry.get(run.runId()).status()).isEqualTo(RunStatus.QUEUED);
      assertThatThrownBy(() -> shell.submit("consult", AgentRunContext.Mode.CONVERSATION))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(registry.runIds()).containsExactly(run.runId());
    }
  }
}
