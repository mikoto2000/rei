package dev.mikoto2000.rei.event;

import org.springframework.stereotype.Component;

/** 実行状況の通知に必要なプロジェクトエージェントイベント購読先を扱う。 */
@Component
public class ProjectAgentEventSubscriber implements AutoCloseable {
  /** 購読／境界。 */
  private final AgentEventBus.Subscription subscription, boundary;

  /** 永続化済み。 */
  private final java.util.LinkedHashSet<String> persisted = new java.util.LinkedHashSet<>();

  /** 指定された値や利用先を使って、ProjectAgentEventSubscriberを初期化する。 */
  public ProjectAgentEventSubscriber(AgentEventBus bus, ProjectAgentEventStore store) {
    boundary =
        bus.subscribeBoundary(
            event -> {
              if (event.projectId() != null) {
                store.append(event);
                synchronized (persisted) {
                  persisted.add(event.id());
                  if (persisted.size() > 1024) persisted.remove(persisted.iterator().next());
                }
              }
            });
    subscription =
        bus.subscribe(
            event -> {
              boolean already;
              synchronized (persisted) {
                already = persisted.remove(event.id());
              }
              if (!already) store.onEvent(event);
            });
  }

  /** 実行状況の通知で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    boundary.unsubscribe();
    subscription.unsubscribe();
  }
}
