package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ReplayGapException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web APIによる操作に必要なイベント配信連携を扱う。
 *
 * <p>EventBus callbacks only offer to a bounded queue. Socket writes run on dedicated virtual
 * threads.
 */
public final class SseBridge implements AutoCloseable {
  /** Web APIによる操作に必要な出力先を扱う。 */
  public interface Sink {
    /** Web APIによる操作に関するイベントを処理する。 */
    void event(WebApiEventDto event) throws Exception;

    /** Web APIによる操作に関する生存確認を処理する。 */
    void heartbeat() throws Exception;

    /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
    void complete(Throwable error);
  }

  /**
   * Web APIによる操作で受け渡す観測単位を表す。
   *
   * @param event イベント
   */
  private record Frame(AgentEvent event) {}

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 実行一覧。 */
  private final RunService runs;

  /** APIキー。 */
  private final String apiKey;

  /** 書き込み先一覧。 */
  private final ExecutorService writers;

  /** 接続継続の確認を予約する実行先。 */
  private final ScheduledExecutorService heartbeats;

  /** 接続一覧。 */
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

  /** 受付から終了までの管理購読。 */
  private final AgentEventBus.Subscription lifecycleSubscription;

  /** 終了イベント。 */
  private volatile AgentEvent shutdownEvent;

  /** 終了配信。 */
  private volatile CompletableFuture<Void> shutdownDelivery =
      CompletableFuture.completedFuture(null);

  /** Web APIによる操作を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SseBridge(AgentEventBus bus, RunService runs, String apiKey) {
    this(
        bus,
        runs,
        apiKey,
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rei-sse-", 0).factory()),
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("rei-sse-heartbeat").factory()));
  }

  /** 指定された値や利用先を使って、SseBridgeを初期化する。 */
  SseBridge(
      AgentEventBus bus,
      RunService runs,
      String apiKey,
      ExecutorService writers,
      ScheduledExecutorService heartbeats) {
    this.bus = bus;
    this.runs = runs;
    this.apiKey = apiKey;
    this.writers = writers;
    this.heartbeats = heartbeats;
    lifecycleSubscription =
        bus.subscribe(
            event -> {
              if (event.type() != AgentEventType.APPLICATION_SHUTDOWN_STARTED) return;
              shutdownEvent = event;
              var active = java.util.List.copyOf(connections);
              shutdownDelivery =
                  CompletableFuture.allOf(
                      active.stream()
                          .map(connection -> connection.deliveryComplete)
                          .toArray(CompletableFuture[]::new));
              // Live observers offer shutdown after all earlier queued run frames.
            });
  }

  /** Web APIによる操作で用いる接続を返す。 */
  public Connection connect(String runId, Sink sink) {
    return connect(runId, null, sink);
  }

  /** Web APIによる操作で用いる接続を返す。 */
  public Connection connect(String runId, Long lastEventId, Sink sink) {
    var connection =
        bus.withReplaySnapshot(
            () -> {
              var run = runs.get(runId);
              if (run.status() == RunStatus.UNKNOWN) throw new ReplayGapException();
              var registered = new Connection(runId, sink);
              var replay =
                  bus.subscribeObserver(
                      runId,
                      lastEventId == null ? 0 : lastEventId,
                      event -> registered.offer(new Frame(event)),
                      event -> event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED);
              // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
              if (run.status().isTerminal()
                  && runs.restored(runId)
                  && replay.terminalSequence() == null) {
                replay.subscription().unsubscribe();
                throw new ReplayGapException();
              }
              registered.subscription = replay.subscription();
              registered.replay = replay.replay();
              registered.alreadyComplete =
                  run.status().isTerminal()
                      && replay.terminalSequence() != null
                      && lastEventId != null
                      && lastEventId >= replay.terminalSequence();
              connections.add(registered);
              if (shutdownEvent != null) {
                registered.offer(new Frame(shutdownEvent));
                shutdownDelivery =
                    CompletableFuture.allOf(shutdownDelivery, registered.deliveryComplete);
              }
              return registered;
            });
    connection.start();
    return connection;
  }

  /** Web APIによる操作に必要な接続を扱う。 */
  public final class Connection implements AutoCloseable {
    /** 実行の識別子。 */
    private final String runId;

    /** 出力先。 */
    private final Sink sink;

    /** 実行待ち。 */
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(1000);

    /** 終了済み。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 配信完了。 */
    private final CompletableFuture<Void> deliveryComplete = new CompletableFuture<>();

    /** 購読。 */
    private volatile AgentEventBus.Subscription subscription;

    /** 書き込み先。 */
    private volatile Future<?> writer;

    /** 生存確認。 */
    private volatile Future<?> heartbeat;

    /** 再送。 */
    private java.util.List<AgentEvent> replay = java.util.List.of();

    /** 既に完了。 */
    private boolean alreadyComplete;

    /** 指定された値や利用先を使って、Connectionを初期化する。 */
    private Connection(String runId, Sink sink) {
      this.runId = runId;
      this.sink = sink;
    }

    /** 要求された処理を開始する。 */
    private void start() {
      if (alreadyComplete) {
        finish(null);
        return;
      }
      writer = writers.submit(this::write);
      heartbeat =
          heartbeats.scheduleAtFixedRate(() -> offer(new Frame(null)), 20, 20, TimeUnit.SECONDS);
      if (closed.get()) cleanup();
    }

    /** Web APIによる操作に関する受付を処理する。 */
    private void offer(Frame frame) {
      if (!closed.get() && !queue.offer(frame))
        finish(new IOException("SSE consumer queue overflow"));
    }

    /** 指定された内容を書き込み先へ反映する。 */
    private void write() {
      try {
        // Replay has its own bounded snapshot; do not dump 10,000 retained events into a 1,000-item
        // live queue.
        for (var event : replay) {
          if (closed.get() || writeEvent(event)) return;
        }
        replay = java.util.List.of();
        while (!closed.get()) {
          var frame = queue.take();
          if (frame.event() == null) {
            sink.heartbeat();
            continue;
          }
          if (writeEvent(frame.event())) return;
        }
      } catch (Exception error) {
        finish(error);
      }
    }

    /** Web APIによる操作において、書き込みイベントに該当するか判定する。 */
    private boolean writeEvent(AgentEvent event) throws Exception {
      boolean shuttingDown = event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED;
      boolean cancelled = !shuttingDown && runs.streamStatus(runId) == RunStatus.CANCELLED;
      var dto = WebApiEventMapper.from(event, cancelled, apiKey);
      sink.event(dto);
      if (shuttingDown
          || Set.of("agent.run.completed", "agent.run.failed", "agent.run.cancelled")
              .contains(dto.type())) {
        finish(null);
        return true;
      }
      return false;
    }

    /** 終了時に必要な状態を反映し、処理を完了させる。 */
    private void finish(Throwable error) {
      // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
      if (!closed.compareAndSet(false, true)) return;
      cleanup();
      // Even error completion can interact with servlet IO: never invoke it on the publisher
      // thread.
      writers.execute(
          () -> {
            try {
              sink.complete(error);
            } finally {
              deliveryComplete.complete(null);
            }
          });
    }

    /** Web APIによる操作において、終了済みかどうかを判定する。 */
    public boolean isClosed() {
      return closed.get();
    }

    /** Web APIによる操作で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) {
        cleanup();
        deliveryComplete.complete(null);
      }
    }

    /** Web APIによる操作に関する後片付けを処理する。 */
    private void cleanup() {
      if (subscription != null) subscription.unsubscribe();
      if (heartbeat != null) heartbeat.cancel(false);
      if (writer != null) writer.cancel(true);
      queue.clear();
      connections.remove(this);
    }
  }

  /** Web APIによる操作で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    lifecycleSubscription.unsubscribe();
    // A slow socket must not hold up shutdown indefinitely, but queued lifecycle
    // notifications need a chance to reach clients before writers are interrupted.
    try {
      shutdownDelivery.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException ignored) {
    }
    connections.forEach(Connection::close);
    heartbeats.shutdownNow();
    writers.shutdownNow();
  }
}
