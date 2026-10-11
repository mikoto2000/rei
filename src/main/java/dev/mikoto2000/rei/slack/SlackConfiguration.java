package dev.mikoto2000.rei.slack;

import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"rei.slack.enabled","rei.backend-mode"},havingValue="true")
@EnableConfigurationProperties(SlackProperties.class)
public class SlackConfiguration {
  @Bean SlackSocketLifecycle slackSocketLifecycle(SlackProperties p,@Value("${rei.data-dir}") String root,Clock clock,
      SessionRepository repository,ProjectRegistry projects,RunRegistry registry,RunService runs,ConversationInputRouter router,AgentEventBus bus) {
    return new SlackSocketLifecycle(p,true,System.getenv(),()->SlackSdkConnection.open(p,Path.of(root),clock,repository,projects,registry,runs,router,bus));
  }
  /** A headless backend can use Socket Mode without enabling the existing HTTP API. */
  @Configuration(proxyBeanMethods=false)
  @ConditionalOnProperty(name="rei.web.enabled",havingValue="false",matchIfMissing=true)
  static class HeadlessRuns {
    @Bean ProjectRegistry slackProjectRegistry(@Value("${rei.data-dir}") String root){return new ProjectRegistry(Path.of(root).resolve("projects.json"));}
    @Bean RunRegistry slackRunRegistry(Clock clock,@Qualifier("memoryConsolidationDataSource") DataSource source){return new RunRegistry(clock,source);}
    @Bean RunService slackRunService(RunRegistry registry,AgentEventBus bus,AgentEventFactory events,CommandCancellationService cancellation,ConversationInputRouter router){return new RunService(registry,bus,events,cancellation,router::cancelQueued);}
  }
}
