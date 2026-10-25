package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** WebAPIの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@Import({
  dev.mikoto2000.rei.application.project.ProjectQueryService.class,
  dev.mikoto2000.rei.conversation.SessionHistoryConfiguration.class,
  dev.mikoto2000.rei.application.task.TaskManagerConfiguration.class,
  dev.mikoto2000.rei.artifact.ArtifactConfiguration.class,
  dev.mikoto2000.rei.github.GitHubWebhookConfiguration.class,
  dev.mikoto2000.rei.planning.DailyPlanningConfiguration.class
})
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class WebApiConfiguration {
  /** Web APIによる操作で用いるWebプロジェクト登録台帳を返す。 */
  @Bean
  ProjectRegistry webProjectRegistry(
      @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ProjectRegistry(java.nio.file.Path.of(directory).resolve("projects.json"));
  }

  /** Web APIによる操作で用いるWeb実行登録台帳を返す。 */
  @Bean
  RunRegistry webRunRegistry(
      Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
          org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> source,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.conversation.concurrent-enabled:false}")
          boolean enabled,
      @org.springframework.beans.factory.annotation.Value("${rei.task-manager.enabled:false}")
          boolean tasksEnabled,
      @org.springframework.beans.factory.annotation.Value("${rei.today.enabled:false}")
          boolean todayEnabled,
      @org.springframework.beans.factory.annotation.Value("${rei.slack.enabled:false}")
          boolean slackEnabled) {
    return new RunRegistry(
        clock, enabled || tasksEnabled || todayEnabled || slackEnabled ? source.getObject() : null);
  }

  /** Web APIによる操作で用いるWeb会話登録台帳を返す。 */
  @Bean
  SessionRegistry webSessionRegistry(Clock clock) {
    return new SessionRegistry(clock);
  }

  /** Web APIによる操作で用いるWeb実行サービスを返す。 */
  @Bean
  RunService webRunService(
      RunRegistry registry,
      AgentEventBus bus,
      AgentEventFactory events,
      CommandCancellationService cancellation,
      ConversationInputRouter router) {
    return new RunService(registry, bus, events, cancellation, router::cancelQueued);
  }

  /** Web APIによる操作で用いるWebチャット受付サービスを返す。 */
  @Bean
  ChatSubmitService webChatSubmitService(
      ProjectRegistry projects,
      SessionRegistry sessions,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle,
      @org.springframework.beans.factory.annotation.Value(
              "${rei.conversation.concurrent-enabled:false}")
          boolean concurrentEnabled) {
    return new ChatSubmitService(
        projects,
        sessions,
        registry,
        lifecycle,
        (context, prompt) -> router.submit(context, prompt, work -> runs.execute(context, work)),
        concurrentEnabled);
  }

  /** Web APIによる操作で用いるイベント配信連携を返す。 */
  @Bean
  SseBridge sseBridge(AgentEventBus bus, RunService runs, ApiKeyProperties key) {
    return new SseBridge(bus, runs, key.getApiKey());
  }

  /** Web APIによる操作で用いるWeb保守を返す。 */
  @Bean
  Maintenance webMaintenance(RunService runs, SessionRegistry sessions) {
    return new Maintenance(runs, sessions);
  }

  /** Web APIによる操作に必要な保守を扱う。 */
  static final class Maintenance {
    /** 実行一覧。 */
    private final RunService runs;

    /** 会話一覧。 */
    private final SessionRegistry sessions;

    /** 指定された値や利用先を使って、Maintenanceを初期化する。 */
    Maintenance(RunService runs, SessionRegistry sessions) {
      this.runs = runs;
      this.sessions = sessions;
    }

    /** Web APIによる操作に関する保持期限を過ぎた情報の削除を処理する。 */
    @Scheduled(fixedDelay = 60_000)
    public void purge() {
      runs.purgeExpired();
      sessions.purgeExpired();
    }
  }
}
