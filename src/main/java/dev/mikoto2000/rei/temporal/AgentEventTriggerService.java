package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * エージェントイベント実行条件のユースケースを実現する。
 *
 * <p>Live facts plus bounded durable replay. The existing dispatcher owns all execution.
 */
@Component
public class AgentEventTriggerService implements AutoCloseable {
  /** 予約されたエージェント実行を管理する窓口。 */
  private final PersistentAgentScheduler schedules;

  /** 保存先。 */
  private final ProjectAgentEventStore store;

  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 変更後プロジェクト。 */
  private String afterProject = "";

  /** 指定された値や利用先を使って、AgentEventTriggerServiceを初期化する。 */
  public AgentEventTriggerService(
      PersistentAgentScheduler schedules, AgentEventBus bus, ProjectAgentEventStore store) {
    this.schedules = schedules;
    this.store = store;
    subscription = bus.subscribe(schedules::signalEvent);
  }

  /** 時刻と期間の解釈に関する定期確認を処理する。 */
  @Scheduled(fixedDelayString = "${rei.agent-scheduler.event-check-interval-ms:5000}")
  public synchronized void tick() {
    schedules.expireEventWaits();
    var projects = schedules.waitingEventProjects(afterProject);
    if (projects.isEmpty()) {
      afterProject = "";
      projects = schedules.waitingEventProjects(afterProject);
    }
    for (String project : projects) {
      afterProject = project;
      try {
        var cursor = schedules.replayCursor(project);
        var page = store.readPage(project, cursor.offset(), cursor.discardingLine());
        for (var event : page.events())
          if (project.equals(event.projectId())) schedules.signalEvent(event);
        schedules.saveReplayCursor(project, cursor, page.nextOffset(), page.discardingLine());
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Schedule event replay failed: {}", error.getClass().getSimpleName());
      }
    }
  }

  /** 時刻と期間の解釈で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
