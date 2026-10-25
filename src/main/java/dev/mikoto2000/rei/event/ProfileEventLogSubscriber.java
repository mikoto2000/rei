package dev.mikoto2000.rei.event;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 実行状況の通知に必要な利用者情報イベントログ購読先を扱う。 */
@Component
@RequiredArgsConstructor
public class ProfileEventLogSubscriber {

  /** 実行状況を共有する通知先。 */
  private final AgentEventBus eventBus;

  /** ログ保存先。 */
  private final ProfileEventLogStore logStore;

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private StreamingEventPersistence streaming;

  /** 実行状況の通知に関する購読を処理する。 */
  @PostConstruct
  void subscribe() {
    subscription =
        eventBus.subscribe(
            event -> {
              if (streaming == null || !streaming.handles(event)) logStore.onEvent(event);
            });
  }

  /** 実行状況の通知に関する購読の終了を処理する。 */
  @PreDestroy
  void unsubscribe() {
    if (subscription != null) {
      subscription.unsubscribe();
    }
  }
}
