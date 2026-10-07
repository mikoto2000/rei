package dev.mikoto2000.rei.github;
import javax.sql.DataSource;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.attention.AttentionRepository;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"rei.web.enabled","rei.github.webhook.enabled"},havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GitHubWebhookProperties.class)
@Import(dev.mikoto2000.rei.web.GitHubWebhookController.class)
public class GitHubWebhookConfiguration {
  @Bean
  GitHubNotificationOutbox gitHubNotificationOutbox(GitHubFactRepository facts,ProjectRegistry projects,dev.mikoto2000.rei.event.AgentEventPublisher publisher){return new GitHubNotificationOutbox(facts,projects,publisher);}
  @Bean GitHubFactRepository gitHubFactRepository(@org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock){return new GitHubFactRepository(source,clock);}
  @Bean GitHubWebhookService gitHubWebhookService(GitHubWebhookProperties properties,Clock clock,ProjectRegistry projects,
      SessionLifecycle sessions,GitHubFactRepository facts,PersistentAgentScheduler scheduler,AttentionRepository inbox,
      org.springframework.beans.factory.ObjectProvider<GitHubStateVerifier> verifier) {
    return new GitHubWebhookService(properties,clock,projects,sessions,facts,scheduler,inbox,verifier.getIfAvailable());
  }
}
