package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import({dev.mikoto2000.rei.conversation.SessionHistoryConfiguration.class,dev.mikoto2000.rei.application.task.TaskManagerConfiguration.class,dev.mikoto2000.rei.artifact.ArtifactConfiguration.class,dev.mikoto2000.rei.github.GitHubWebhookConfiguration.class,dev.mikoto2000.rei.planning.DailyPlanningConfiguration.class})
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class WebApiConfiguration {
  @Bean
  ProjectRegistry webProjectRegistry(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ProjectRegistry(java.nio.file.Path.of(directory).resolve("projects.json"));
  }
  @Bean RunRegistry webRunRegistry(Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> source,
      @org.springframework.beans.factory.annotation.Value("${rei.conversation.concurrent-enabled:false}") boolean enabled,
      @org.springframework.beans.factory.annotation.Value("${rei.task-manager.enabled:false}") boolean tasksEnabled,
      @org.springframework.beans.factory.annotation.Value("${rei.today.enabled:false}") boolean todayEnabled) {
    return new RunRegistry(clock,enabled||tasksEnabled||todayEnabled?source.getObject():null);
  }
  @Bean dev.mikoto2000.rei.application.project.ProjectQueryService webProjectQueryService(ProjectRegistry projects) {
    return new dev.mikoto2000.rei.application.project.ProjectQueryService(projects);
  }
  @Bean SessionRegistry webSessionRegistry(Clock clock) { return new SessionRegistry(clock); }
  @Bean RunService webRunService(RunRegistry registry, AgentEventBus bus, AgentEventFactory events,
      CommandCancellationService cancellation, ConversationInputRouter router) {
    return new RunService(registry, bus, events, cancellation, router::cancelQueued);
  }
  @Bean ChatSubmitService webChatSubmitService(ProjectRegistry projects, SessionRegistry sessions,
      RunRegistry registry, RunService runs, ConversationInputRouter router,
      dev.mikoto2000.rei.application.session.SessionLifecycle lifecycle,
      @org.springframework.beans.factory.annotation.Value("${rei.conversation.concurrent-enabled:false}") boolean concurrentEnabled) {
    return new ChatSubmitService(projects, sessions, registry, lifecycle,
        (context, prompt) -> router.submit(context, prompt, work -> runs.execute(context, work)), concurrentEnabled);
  }
  @Bean SseBridge sseBridge(AgentEventBus bus, RunService runs, ApiKeyProperties key) {
    return new SseBridge(bus, runs, key.getApiKey());
  }
  @Bean Maintenance webMaintenance(RunService runs, SessionRegistry sessions) { return new Maintenance(runs, sessions); }
  static final class Maintenance {
    private final RunService runs;
    private final SessionRegistry sessions;
    Maintenance(RunService runs, SessionRegistry sessions) { this.runs = runs; this.sessions = sessions; }
    @Scheduled(fixedDelay = 60_000) public void purge() { runs.purgeExpired(); sessions.purgeExpired(); }
  }
}
