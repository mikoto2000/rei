package dev.mikoto2000.rei.slack;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Slack機能の構成の結果と状態の契約を検証するテスト。 */
class SlackConfigurationTest {
  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void componentScanningNestedConfigurationDoesNotEnableSlackByDefault() {
    new ApplicationContextRunner()
        .withUserConfiguration(SlackConfiguration.HeadlessRuns.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(dev.mikoto2000.rei.application.run.RunRegistry.class);
            });
  }

  /** 検証に使うディレクトリ。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

  /** 設定済みSlack入力先シェルについて、共有実行機能の構成要素一覧を作成することを確認する。 */
  @Test
  void configuredSlackInShellCreatesSharedRunBeansWithoutWebApi() {
    shellRunner()
        .withUserConfiguration(SlackConfiguration.HeadlessRuns.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .hasSingleBean(dev.mikoto2000.rei.application.run.RunRegistry.class);
              assertThat(context)
                  .hasSingleBean(dev.mikoto2000.rei.application.run.RunService.class);
              assertThat(context)
                  .hasSingleBean(dev.mikoto2000.rei.core.project.ProjectRegistry.class);
            });
  }

  /** 設定済みシェルについて、通信の接続受付から終了までの管理を作成することを確認する。 */
  @Test
  void configuredShellCreatesSocketLifecycleWithoutStartingNetworkInContextTest() {
    shellRunner()
        .withUserConfiguration(SlackConfiguration.class)
        .withBean(
            dev.mikoto2000.rei.application.session.SessionRepository.class,
            () ->
                org.mockito.Mockito.mock(
                    dev.mikoto2000.rei.application.session.SessionRepository.class))
        .withBean(
            dev.mikoto2000.rei.conversation.ConversationTurnStore.class,
            () ->
                org.mockito.Mockito.mock(
                    dev.mikoto2000.rei.conversation.ConversationTurnStore.class))
        // Suppress automatic SmartLifecycle startup; this test verifies real bean wiring, not Slack
        // credentials.
        .withBean(
            "lifecycleProcessor",
            org.springframework.context.LifecycleProcessor.class,
            () -> org.mockito.Mockito.mock(org.springframework.context.LifecycleProcessor.class))
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(SlackSocketLifecycle.class);
              assertThat(context)
                  .hasSingleBean(dev.mikoto2000.rei.application.run.RunRegistry.class);
            });
  }

  /** {@code shellRunner} の条件を再現するための補助情報を返す。 */
  private ApplicationContextRunner shellRunner() {
    return new ApplicationContextRunner()
        .withPropertyValues(
            "rei.slack.enabled=true",
            "rei.backend-mode=false",
            "rei.web.enabled=false",
            "rei.data-dir=" + dir)
        .withBean(
            "memoryConsolidationDataSource",
            javax.sql.DataSource.class,
            () ->
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:sqlite:" + dir.resolve("storage.db")))
        .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
        .withBean(
            dev.mikoto2000.rei.event.AgentEventBus.class,
            dev.mikoto2000.rei.event.InMemoryAgentEventBus::new)
        .withBean(
            dev.mikoto2000.rei.event.AgentEventFactory.class,
            () -> new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()))
        .withBean(
            dev.mikoto2000.rei.core.service.CommandCancellationService.class,
            dev.mikoto2000.rei.core.service.CommandCancellationService::new)
        .withBean(
            dev.mikoto2000.rei.core.chat.ConversationInputRouter.class,
            () ->
                org.mockito.Mockito.mock(
                    dev.mikoto2000.rei.core.chat.ConversationInputRouter.class));
  }
}
