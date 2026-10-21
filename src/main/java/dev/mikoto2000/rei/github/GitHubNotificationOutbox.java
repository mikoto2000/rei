package dev.mikoto2000.rei.github;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEventPublisher;

/**
 * GitHubとの連携に必要なGit連携先通知Outboxを扱う。
 *
 * <p>Publishes committed notices with stable IDs. Delivery consumers deduplicate retries.
 */
public final class GitHubNotificationOutbox {
  /** 事実一覧。 */
  private final GitHubFactRepository facts;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 通知先。 */
  private final AgentEventPublisher publisher;

  /** 指定された値や利用先を使って、GitHubNotificationOutboxを初期化する。 */
  public GitHubNotificationOutbox(
      GitHubFactRepository facts, ProjectRegistry projects, AgentEventPublisher publisher) {
    this.facts = facts;
    this.projects = projects;
    this.publisher = publisher;
  }

  /** GitHubとの連携に関する保存の確定を処理する。 */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${rei.github.webhook.notification-poll-ms:5000}")
  public synchronized void flush() {
    for (var notice : facts.pendingNotifications()) {
      if (projects
          .resolveById(notice.projectId())
          .filter(project -> project.root().toString().equals(notice.root()))
          .isEmpty()) {
        facts.notificationSuppressed(notice.id());
        continue;
      }
      try {
        publisher.publishBoundary(notice.event());
        facts.notificationSent(notice.id());
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn(
                "GitHub notification publication remains pending: {}",
                failure.getClass().getSimpleName());
      }
    }
  }
}
