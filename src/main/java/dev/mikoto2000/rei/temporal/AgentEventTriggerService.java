package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * エージェントイベント実行条件のユースケースを実現する。
 *
 * <p>Live facts plus bounded durable replay. The existing dispatcher owns all execution.
 */
@Component
public final class AgentEventTriggerService implements AutoCloseable {
  private final Runnable expireEventWaits;
  private final Function<String, List<String>> waitingEventProjects;

  private final Consumer<String> replayProjectPage;

  /** 購読。 */
  private final AgentEventBus.Subscription subscription;

  /** 変更後プロジェクト。 */
  private String afterProject = "";

  /** 指定された値や利用先を使って、AgentEventTriggerServiceを初期化する。 */
  public AgentEventTriggerService(
      PersistentAgentScheduler schedules, AgentEventBus bus, ProjectAgentEventStore store) {
    this.expireEventWaits = schedules::expireEventWaits;
    this.waitingEventProjects = schedules::waitingEventProjects;
    this.replayProjectPage = project -> replayProjectPage(schedules, store, project);
    subscription = bus.subscribe(schedules::signalEvent);
  }

  /** 時刻と期間の解釈に関する定期確認を処理する。 */
  @Scheduled(fixedDelayString = "${rei.agent-scheduler.event-check-interval-ms:5000}")
  public synchronized void tick() {
    expireEventWaits.run();
    var projects = waitingEventProjects.apply(afterProject);
    if (projects.isEmpty()) {
      afterProject = "";
      projects = waitingEventProjects.apply(afterProject);
    }
    for (String project : projects) {
      afterProject = project;
      try {
        replayProjectPage.accept(project);
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass())
            .warn("Schedule event replay failed: {}", error.getClass().getSimpleName());
      }
    }
  }

  private static void replayProjectPage(
      PersistentAgentScheduler schedules, ProjectAgentEventStore store, String project) {
    var cursor = schedules.replayCursor(project);
    var page = store.readPage(project, cursor.offset(), cursor.discardingLine());
    for (var event : page.events())
      if (project.equals(event.projectId())) schedules.signalEvent(event);
    schedules.saveReplayCursor(project, cursor, page.nextOffset(), page.discardingLine());
  }

  /** 時刻と期間の解釈で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    subscription.unsubscribe();
  }
}
