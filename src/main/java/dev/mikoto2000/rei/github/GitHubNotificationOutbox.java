package dev.mikoto2000.rei.github;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventPublisher;
/** Publishes committed notices with stable IDs. Delivery consumers deduplicate retries. */
public final class GitHubNotificationOutbox {
  private final GitHubFactRepository facts;private final ProjectRegistry projects;private final AgentEventPublisher publisher;
  public GitHubNotificationOutbox(GitHubFactRepository facts,ProjectRegistry projects,AgentEventPublisher publisher){this.facts=facts;this.projects=projects;this.publisher=publisher;}
  @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${rei.github.webhook.notification-poll-ms:5000}")
  public synchronized void flush(){
    for(var notice:facts.pendingNotifications()) {
      if(projects.resolveById(notice.projectId()).filter(project->project.root().toString().equals(notice.root())).isEmpty()){facts.notificationSuppressed(notice.id());continue;}
      try{publisher.publishBoundary(notice.event());facts.notificationSent(notice.id());}
      catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("GitHub notification publication remains pending: {}",failure.getClass().getSimpleName());}
    }
  }
}
