package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.event.*;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** EventBus callbacks only offer to a bounded queue. Socket writes run on dedicated virtual threads. */
public final class SseBridge implements AutoCloseable {
  public interface Sink {
    void event(WebApiEventDto event) throws Exception;
    void heartbeat() throws Exception;
    void complete(Throwable error);
  }
  private record Frame(AgentEvent event) {}
  private final AgentEventBus bus;
  private final RunService runs;
  private final String apiKey;
  private final ExecutorService writers;
  private final ScheduledExecutorService heartbeats;
  private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
  private final AgentEventBus.Subscription lifecycleSubscription;
  private volatile AgentEvent shutdownEvent;
  private volatile CompletableFuture<Void> shutdownDelivery = CompletableFuture.completedFuture(null);

  public SseBridge(AgentEventBus bus, RunService runs, String apiKey) {
    this(bus, runs, apiKey, Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("rei-sse-", 0).factory()),
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("rei-sse-heartbeat").factory()));
  }
  SseBridge(AgentEventBus bus, RunService runs, String apiKey, ExecutorService writers, ScheduledExecutorService heartbeats) {
    this.bus = bus; this.runs = runs; this.apiKey = apiKey; this.writers = writers; this.heartbeats = heartbeats;
    lifecycleSubscription = bus.subscribe(event -> {
      if (event.type() != AgentEventType.APPLICATION_SHUTDOWN_STARTED) return;
      shutdownEvent = event;
      var active = java.util.List.copyOf(connections);
      shutdownDelivery = CompletableFuture.allOf(active.stream()
          .map(connection -> connection.deliveryComplete).toArray(CompletableFuture[]::new));
      active.forEach(connection -> connection.offer(new Frame(event)));
    });
  }
  public Connection connect(String runId, Sink sink) {
    return connect(runId, null, sink);
  }
  public Connection connect(String runId, Long lastEventId, Sink sink) {
    Connection connection;
    synchronized (bus) {
      var run = runs.get(runId);
      connection = new Connection(runId, sink);
      var replay = bus.subscribe(runId, lastEventId == null ? 0 : lastEventId,
          event -> connection.offer(new Frame(event)));
      connection.subscription = replay.subscription();
      connection.replay = replay.replay();
      connection.alreadyComplete = run.status().isTerminal() && replay.terminalSequence() != null
          && lastEventId != null && lastEventId >= replay.terminalSequence();
      connections.add(connection);
      if (shutdownEvent != null) {
        connection.offer(new Frame(shutdownEvent));
        shutdownDelivery = CompletableFuture.allOf(shutdownDelivery, connection.deliveryComplete);
      }
    }
    connection.start();
    return connection;
  }
  public final class Connection implements AutoCloseable {
    private final String runId;
    private final Sink sink;
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(1000);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> deliveryComplete = new CompletableFuture<>();
    private volatile AgentEventBus.Subscription subscription;
    private volatile Future<?> writer;
    private volatile Future<?> heartbeat;
    private java.util.List<AgentEvent> replay = java.util.List.of();
    private boolean alreadyComplete;
    private Connection(String runId, Sink sink) { this.runId = runId; this.sink = sink; }
    private void start() {
      if (alreadyComplete) { finish(null); return; }
      writer = writers.submit(this::write);
      heartbeat = heartbeats.scheduleAtFixedRate(() -> offer(new Frame(null)), 20, 20, TimeUnit.SECONDS);
      if (closed.get()) cleanup();
    }
    private void offer(Frame frame) {
      if (!closed.get() && !queue.offer(frame)) finish(new IOException("SSE consumer queue overflow"));
    }
    private void write() {
      try {
        // Replay has its own bounded snapshot; do not dump 10,000 retained events into a 1,000-item live queue.
        for (var event : replay) { if (closed.get() || writeEvent(event)) return; }
        replay = java.util.List.of();
        while (!closed.get()) {
          var frame = queue.take();
          if (frame.event() == null) { sink.heartbeat(); continue; }
          if (writeEvent(frame.event())) return;
        }
      } catch (Exception error) { finish(error); }
    }
    private boolean writeEvent(AgentEvent event) throws Exception {
      boolean shuttingDown = event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED;
      boolean cancelled = !shuttingDown && runs.get(runId).status() == RunStatus.CANCELLED;
      var dto = WebApiEventMapper.from(event, cancelled, apiKey);
      sink.event(dto);
      if (shuttingDown || Set.of("agent.run.completed", "agent.run.failed", "agent.run.cancelled").contains(dto.type())) {
        finish(null); return true;
      }
      return false;
    }
    private void finish(Throwable error) {
      if (!closed.compareAndSet(false, true)) return;
      cleanup();
      // Even error completion can interact with servlet IO: never invoke it on the publisher thread.
      writers.execute(() -> {
        try { sink.complete(error); }
        finally { deliveryComplete.complete(null); }
      });
    }
    public boolean isClosed() { return closed.get(); }
    @Override public void close() {
      if (closed.compareAndSet(false, true)) {
        cleanup();
        deliveryComplete.complete(null);
      }
    }
    private void cleanup() {
      if (subscription != null) subscription.unsubscribe();
      if (heartbeat != null) heartbeat.cancel(false);
      if (writer != null) writer.cancel(true);
      queue.clear(); connections.remove(this);
    }
  }
  @Override public void close() {
    lifecycleSubscription.unsubscribe();
    // A slow socket must not hold up shutdown indefinitely, but queued lifecycle
    // notifications need a chance to reach clients before writers are interrupted.
    try { shutdownDelivery.get(2, TimeUnit.SECONDS); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    catch (ExecutionException | TimeoutException ignored) { }
    connections.forEach(Connection::close);
    heartbeats.shutdownNow(); writers.shutdownNow();
  }
}
