package dev.mikoto2000.rei.github;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.attention.AttentionRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Git連携先Webhook通知の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    name = {"rei.web.enabled", "rei.github.webhook.enabled"},
    havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(GitHubWebhookProperties.class)
@Import(dev.mikoto2000.rei.web.GitHubWebhookController.class)
public class GitHubWebhookConfiguration {
  /** GitHubとの連携で用いるGit連携先通知Outboxを返す。 */
  @Bean
  GitHubNotificationOutbox gitHubNotificationOutbox(
      GitHubFactRepository facts,
      ProjectRegistry projects,
      dev.mikoto2000.rei.event.AgentEventPublisher publisher) {
    return new GitHubNotificationOutbox(facts, projects, publisher);
  }

  /** GitHubとの連携で用いるGit連携先事実保存と検索の窓口を返す。 */
  @Bean
  GitHubFactRepository gitHubFactRepository(
      @org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource")
          DataSource source,
      Clock clock) {
    return new GitHubFactRepository(source, clock);
  }

  /** GitHubとの連携で用いるGit連携先Webhook通知サービスを返す。 */
  @Bean
  GitHubWebhookService gitHubWebhookService(
      GitHubWebhookProperties properties,
      Clock clock,
      ProjectRegistry projects,
      SessionLifecycle sessions,
      GitHubFactRepository facts,
      PersistentAgentScheduler scheduler,
      AttentionRepository inbox,
      org.springframework.beans.factory.ObjectProvider<GitHubStateVerifier> verifier) {
    return new GitHubWebhookService(
        properties, clock, projects, sessions, facts, scheduler, inbox, verifier.getIfAvailable());
  }
}
