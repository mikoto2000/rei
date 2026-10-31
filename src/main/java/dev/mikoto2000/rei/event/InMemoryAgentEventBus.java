package dev.mikoto2000.rei.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * プロセス内 In-memory Event Bus。
 *
 * <p>publish されたイベントに単調増加の sequence を付与し、各 Listener に通知する。 Listener の例外は Agent Core
 * 全体の実行を止めないよう捕捉し、logging で観測可能にする。
 */
@Component
public class InMemoryAgentEventBus implements AgentEventBus, AgentEventPublisher {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(InMemoryAgentEventBus.class);

  /** 通知の受け取り先一覧。 */
  private final List<AgentEventListener> listeners = new CopyOnWriteArrayList<>();

  /** 境界通知の受け取り先一覧。 */
  private final List<AgentEventListener> boundaryListeners = new CopyOnWriteArrayList<>();

  /** replay・表示より先に確定し、保存失敗を発行元へ返す購読先。 */
  private final List<AgentEventListener> persistenceListeners = new CopyOnWriteArrayList<>();

  @Override
  public Subscription subscribePersistence(AgentEventListener listener) {
    java.util.Objects.requireNonNull(listener);
    persistenceListeners.add(listener);
    return () -> persistenceListeners.remove(listener);
  }

  /** 実行状況の通知で用いる購読境界を返す。 */
  @Override
  public Subscription subscribeBoundary(AgentEventListener listener) {
    boundaryListeners.add(listener);
    return () -> boundaryListeners.remove(listener);
  }

  /** 実行状況を利用先で把握できるよう、境界を通知する。 */
  @Override
  public void publishBoundary(AgentEvent event) {
    synchronized (eventLock) {
      java.util.Objects.requireNonNull(event, "event must not be null");
      var sequenced = withSequence(event);
      // Reserve its delivery position before persistence callbacks can reenter.
      pending.addLast(new Pending(sequenced, true));
      boolean active = dispatching;
      dispatching = true;
      try {
        for (var listener : persistenceListeners) listener.onEvent(sequenced);
        for (var listener : boundaryListeners) listener.onEvent(sequenced);
      } catch (RuntimeException | Error failure) {
        pending.clear();
        throw failure;
      } finally {
        dispatching = active;
      }
      drain();
    }
  }

  /** 発生順序。 */
  private final AtomicLong sequence = new AtomicLong(0L);

  private final Object eventLock = new Object();

  /** 待機中。 */
  private final java.util.ArrayDeque<Pending> pending = new java.util.ArrayDeque<>();

  /** 実行先への引き渡し中かどうか。 */
  private boolean dispatching;

  private record Pending(AgentEvent event, boolean persisted) {}

  /** 再送。 */
  private final ReplayBuffer replay;

  /** 実行状況の通知を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public InMemoryAgentEventBus() {
    this(new ReplayBuffer(10_000, java.time.Clock.systemUTC()));
  }

  /** 指定された値や利用先を使って、InMemoryAgentEventBusを初期化する。 */
  private InMemoryAgentEventBus(ReplayBuffer replay) {
    this.replay = replay;
  }

  /** 指定された再送一時保持領域を反映した情報を作成する。 */
  public static InMemoryAgentEventBus withReplayBuffer(ReplayBuffer replay) {
    return new InMemoryAgentEventBus(replay);
  }

  /** 実行状況の通知で用いる購読を返す。 */
  @Override
  public ReplaySubscription subscribe(
      String runId, long fromSequence, AgentEventListener listener) {
    synchronized (eventLock) {
      var snapshot = replay.snapshot(runId, fromSequence);
      var subscription =
          subscribe(
              event -> {
                if (runId.equals(event.runId()) && event.sequence() > snapshot.latestSequence())
                  listener.onEvent(event);
              });
      return new ReplaySubscription(
          snapshot.events(), subscription, snapshot.latestSequence(), snapshot.terminalSequence());
    }
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除実行を処理する。 */
  @Override
  public void purgeRun(String runId) {
    synchronized (eventLock) {
      replay.purgeRun(runId);
    }
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  @Override
  public void purgeExpired() {
    synchronized (eventLock) {
      replay.purgeExpired();
    }
  }

  /** 実行状況の通知に関する保持期限を過ぎた情報の削除期限切れを処理する。 */
  @Override
  public void purgeExpired(java.util.Set<String> protectedRuns) {
    synchronized (eventLock) {
      replay.purgeExpired(protectedRuns);
    }
  }

  /** 実行状況の通知で用いる購読を返す。 */
  @Override
  public Subscription subscribe(AgentEventListener listener) {
    if (listener == null) {
      throw new IllegalArgumentException("listener must not be null");
    }
    listeners.add(listener);
    return new Subscription() {
      /** 購読終了済み。 */
      private volatile boolean unsubscribed = false;

      /** 実行状況の通知に関する購読の終了を処理する。 */
      @Override
      public void unsubscribe() {
        if (unsubscribed) {
          return;
        }
        unsubscribed = true;
        listeners.remove(listener);
      }
    };
  }

  /** 状態や結果を購読先へ通知する。 */
  @Override
  public void publish(AgentEvent event) {
    synchronized (eventLock) {
      if (event == null) {
        throw new IllegalArgumentException("event must not be null");
      }
      enqueue(withSequence(event), false);
    }
  }

  private void enqueue(AgentEvent event, boolean persisted) {
    pending.addLast(new Pending(event, persisted));
    drain();
  }

  private void drain() {
    if (dispatching) return;
    dispatching = true;
    try {
      while (!pending.isEmpty()) {
        var next = pending.removeFirst();
        AgentEvent sequenced = next.event();
        if (!next.persisted())
          for (var listener : persistenceListeners) listener.onEvent(sequenced);
        replay.append(sequenced);
        for (AgentEventListener listener : listeners) {
          try {
            listener.onEvent(sequenced);
          } catch (RuntimeException e) {
            log.warn(
                "Agent event listener failed: type={}, listener={}",
                sequenced.type(),
                listener.getClass().getName(),
                e);
          }
        }
      }
    } catch (RuntimeException | Error failure) {
      // 保存できなかったイベントや再入で待機したイベントを次のpublishへ持ち越さない。
      pending.clear();
      throw failure;
    } finally {
      dispatching = false;
    }
  }

  /** 実行状況の通知で用いる直近発生順序を返す。 */
  @Override
  public long lastSequence() {
    return sequence.get();
  }

  /** 指定された発生順序を反映した情報を作成する。 */
  private AgentEvent withSequence(AgentEvent event) {
    long next = sequence.incrementAndGet();
    return new AgentEvent(
        event.id(),
        next,
        event.timestamp(),
        event.type(),
        event.version(),
        event.sessionId(),
        event.turnId(),
        event.runId(),
        event.correlationId(),
        event.parentEventId(),
        event.payload(),
        event.projectId());
  }
}
