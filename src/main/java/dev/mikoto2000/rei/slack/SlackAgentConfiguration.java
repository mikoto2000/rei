package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.ChatExecutionService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.UserInterventionQueue;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventBus;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Slackエージェントの構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"rei.slack.enabled", "rei.slack.agent-collaboration.enabled"},
    havingValue = "true")
@EnableConfigurationProperties(SlackAgentProperties.class)
public class SlackAgentConfiguration {
  /** Slackとの連携で用いるSlackエージェント保存と検索の窓口を返す。 */
  @Bean
  SlackAgentRepository slackAgentRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    return new SlackAgentRepository(source, clock);
  }

  /** Slackとの連携で用いるSlackエージェント継続入力一覧を返す。 */
  @Bean
  SlackAgentContinuationStore slackAgentContinuations(
      @Qualifier("memoryConsolidationDataSource") DataSource source) {
    return new SlackAgentContinuationStore(source);
  }

  /** Slackとの連携で用いるSlackエージェントサービスを返す。 */
  @Bean
  SlackAgentService slackAgentService(
      SlackAgentProperties p,
      SlackAgentRepository r,
      SlackAgentContinuationStore s,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      Clock clock,
      ObjectProvider<ChatExecutionService> chat,
      ProjectRegistry projects,
      SessionRepository sessions,
      AgentEventBus bus,
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      dev.mikoto2000.rei.core.dependency.DependencyObservationService observations) {
    var service = new SlackAgentService(p, r, s, registry, runs, router, clock);
    service.onValidate(
        owner -> {
          var project =
              projects
                  .resolveById(owner.projectId())
                  .orElseThrow(() -> new IllegalStateException("External wait Project missing"));
          if (!project.root().equals(owner.projectRoot())
              || sessions
                  .findById(owner.conversationId())
                  .filter(session -> session.projectId().equals(owner.projectId()))
                  .isEmpty())
            throw new IllegalStateException("External wait Session/Project moved");
        });
    service.onResume(
        (owner, saved) ->
            chat.getObject()
                .execute(
                    owner,
                    saved.request(),
                    new UserInterventionQueue(),
                    service.reservation(owner.runId())));
    service.dependencies(dependencies, observations);
    return service;
  }

  /** Slackとの連携で用いるSlackエージェントツール一覧を返す。 */
  @Bean
  SlackAgentTools slackAgentTools(
      SlackAgentProperties p,
      SlackAgentRepository r,
      ToolPermissionGuard guard,
      SlackAgentService service) {
    var tools = new SlackAgentTools(p, r, guard);
    tools.onRegistered(service::registered);
    return tools;
  }

  /** Slackとの連携で用いるSlackエージェントイベント一覧を返す。 */
  @Bean(destroyMethod = "unsubscribe")
  AgentEventBus.Subscription slackAgentEvents(SlackAgentService service, AgentEventBus bus) {
    return bus.subscribe(service::onEvent);
  }
}
