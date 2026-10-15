package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventBus;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"rei.slack.enabled", "rei.slack.agent-collaboration.enabled"},
    havingValue = "true")
@EnableConfigurationProperties(SlackAgentProperties.class)
public class SlackAgentConfiguration {
  @Bean
  SlackAgentRepository slackAgentRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    return new SlackAgentRepository(source, clock);
  }

  @Bean
  SlackAgentContinuationStore slackAgentContinuations(
      @Qualifier("memoryConsolidationDataSource") DataSource source) {
    return new SlackAgentContinuationStore(source);
  }

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

  @Bean(destroyMethod = "unsubscribe")
  AgentEventBus.Subscription slackAgentEvents(SlackAgentService service, AgentEventBus bus) {
    return bus.subscribe(service::onEvent);
  }
}
