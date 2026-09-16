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
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
public class WebApiConfiguration {
  @Bean
  ProjectRegistry webProjectRegistry(@org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
    return new ProjectRegistry(java.nio.file.Path.of(directory).resolve("projects.json"));
  }
  @Bean RunRegistry webRunRegistry(Clock clock) { return new RunRegistry(clock); }
  @Bean dev.mikoto2000.rei.application.project.ProjectQueryService webProjectQueryService(ProjectRegistry projects) {
    return new dev.mikoto2000.rei.application.project.ProjectQueryService(projects);
  }
  @Bean SessionRegistry webSessionRegistry(Clock clock) { return new SessionRegistry(clock); }
  @Bean RunService webRunService(RunRegistry registry, AgentEventBus bus, AgentEventFactory events,
      CommandCancellationService cancellation, ConversationInputRouter router) {
    return new RunService(registry, bus, events, cancellation, router::cancelQueued);
  }
  @Bean ChatSubmitService webChatSubmitService(ProjectRegistry projects, SessionRegistry sessions,
      RunRegistry registry, RunService runs, ConversationInputRouter router) {
    return new ChatSubmitService(projects, sessions, registry,
        (context, prompt) -> router.submit(context, prompt, work -> runs.execute(context, work)));
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
