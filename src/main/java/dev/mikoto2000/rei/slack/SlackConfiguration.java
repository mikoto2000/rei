package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Slackの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rei.slack.enabled", havingValue = "true")
@EnableConfigurationProperties(SlackProperties.class)
public class SlackConfiguration {
  /** Slackとの連携で用いるSlack通信の接続受付から終了までの管理を返す。 */
  @Bean
  SlackSocketLifecycle slackSocketLifecycle(
      SlackProperties p,
      @Value("${rei.data-dir}") String root,
      Clock clock,
      SessionRepository repository,
      ProjectRegistry projects,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      AgentEventBus bus,
      dev.mikoto2000.rei.conversation.ConversationTurnStore turns,
      org.springframework.beans.factory.ObjectProvider<SlackAgentService> collaboration) {
    return new SlackSocketLifecycle(
        p,
        System.getenv(),
        () ->
            SlackSdkConnection.open(
                p,
                Path.of(root),
                clock,
                repository,
                projects,
                registry,
                runs,
                router,
                bus,
                turns,
                collaboration.getIfAvailable()));
  }

  /**
   * Slackとの連携に必要なHeadless実行一覧を扱う。
   *
   * <p>A headless backend can use Socket Mode without enabling the existing HTTP API.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "rei.slack.enabled", havingValue = "true")
  @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
      "!${rei.web.enabled:false}")
  static class HeadlessRuns {
    /** Slackとの連携で用いるSlackプロジェクト登録台帳を返す。 */
    @Bean
    ProjectRegistry slackProjectRegistry(@Value("${rei.data-dir}") String root) {
      return new ProjectRegistry(Path.of(root).resolve("projects.json"));
    }

    /** Slackとの連携で用いるSlack実行登録台帳を返す。 */
    @Bean
    RunRegistry slackRunRegistry(
        Clock clock, @Qualifier("memoryConsolidationDataSource") DataSource source) {
      return new RunRegistry(clock, source);
    }

    /** Slackとの連携で用いるSlack実行サービスを返す。 */
    @Bean
    RunService slackRunService(
        RunRegistry registry,
        AgentEventBus bus,
        AgentEventFactory events,
        CommandCancellationService cancellation,
        ConversationInputRouter router) {
      return new RunService(registry, bus, events, cancellation, router::cancelQueued);
    }
  }
}
