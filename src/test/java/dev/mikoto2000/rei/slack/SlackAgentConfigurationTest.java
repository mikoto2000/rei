package dev.mikoto2000.rei.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Slackエージェント機能の構成の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackAgentConfigurationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void defaultsAndDisabledSlackNeverExposeExternalWriteTools() {
    new ApplicationContextRunner()
        .withUserConfiguration(SlackAgentConfiguration.class)
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).doesNotHaveBean(SlackAgentTools.class);
            });
    new ApplicationContextRunner()
        .withUserConfiguration(SlackAgentConfiguration.class)
        .withPropertyValues(
            "rei.slack.enabled=false",
            "rei.slack.agent-collaboration.enabled=true",
            "rei.backend-mode=false")
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              assertThat(c).doesNotHaveBean(SlackAgentTools.class);
            });
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void enabledConfigurationBindsBotIdentityAndObservesOrdinaryRunCancellation(boolean backend) {
    var clock = Clock.systemUTC();
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db"));
    var registry = new RunRegistry(clock);
    var bus = new InMemoryAgentEventBus();
    var owner = new AgentRunContext("run", "session", dir, "project");
    registry.register(owner);
    registry.transition("run", RunStatus.RUNNING, null);
    new ApplicationContextRunner()
        .withUserConfiguration(SlackAgentConfiguration.class)
        .withPropertyValues(
            "rei.slack.enabled=true",
            "rei.backend-mode=" + backend,
            "rei.slack.agent-collaboration.enabled=true",
            "rei.slack.agent-collaboration.allowed-workspace-ids[0]=T1",
            "rei.slack.agent-collaboration.allowed-channel-ids[0]=C1",
            "rei.slack.agent-collaboration.agents.tenten.workspace-id=T1",
            "rei.slack.agent-collaboration.agents.tenten.channel-id=C1",
            "rei.slack.agent-collaboration.agents.tenten.bot-user-id=U2",
            "rei.slack.agent-collaboration.agents.tenten.bot-id=B2",
            "rei.slack.agent-collaboration.agents.tenten.mention-user-id=U2")
        .withBean("memoryConsolidationDataSource", javax.sql.DataSource.class, () -> source)
        .withBean(Clock.class, () -> clock)
        .withBean(RunRegistry.class, () -> registry)
        .withBean(RunService.class, () -> mock(RunService.class))
        .withBean(
            ConversationInputRouter.class,
            () -> new ConversationInputRouter(Runnable::run, (o, p, q) -> {}))
        .withBean(AgentEventBus.class, () -> bus)
        .withBean(
            dev.mikoto2000.rei.core.project.ProjectRegistry.class,
            () -> mock(dev.mikoto2000.rei.core.project.ProjectRegistry.class))
        .withBean(
            dev.mikoto2000.rei.application.session.SessionRepository.class,
            () -> mock(dev.mikoto2000.rei.application.session.SessionRepository.class))
        .withBean(
            dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class,
            () -> mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class))
        .withBean(
            dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class,
            () -> mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class))
        .withBean(
            dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository.class,
            () -> mock(dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository.class))
        .withBean(
            dev.mikoto2000.rei.core.dependency.DependencyObservationService.class,
            () -> mock(dev.mikoto2000.rei.core.dependency.DependencyObservationService.class))
        .run(
            c -> {
              assertThat(c).hasNotFailed();
              var p = c.getBean(SlackAgentProperties.class);
              assertThat(p.agent("tenten").botId()).isEqualTo("B2");
              var repo = c.getBean(SlackAgentRepository.class);
              var q = repo.create(owner, "call", "tenten", "question", "", 300, null, p);
              bus.publish(
                  new AgentEventFactory(clock).runCancelled("run", null).withOwnership(owner));
              assertThat(repo.get(q.id()).state()).isEqualTo("CANCELLED");
            });
  }
}
