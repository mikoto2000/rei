package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.util.function.Predicate;

public class RunService implements AutoCloseable {
  private final RunRegistry registry;
  private final AgentEventBus bus;
  private final AgentEventFactory events;
  private final CommandCancellationService cancellation;
  private final Predicate<String> cancelQueued;
  private final AgentEventBus.Subscription subscription;
  private final java.util.Map<String,Runnable> queuedCancellation=new java.util.HashMap<>();
  public void onQueuedCancellation(String runId,Runnable cancelled) {
    synchronized(monitor()) {
      if(registry.get(runId).status()!=RunStatus.QUEUED||queuedCancellation.putIfAbsent(runId,java.util.Objects.requireNonNull(cancelled))!=null)
        throw new IllegalStateException("Queued cancellation already registered or Run started");
    }
  }
  public void forgetQueuedCancellation(String runId) {synchronized(monitor()){queuedCancellation.remove(runId);}}
  public RunService(RunRegistry registry) { this(registry, null, null, null, null); }
  public RunService(RunRegistry registry, AgentEventBus bus, AgentEventFactory events,
      CommandCancellationService cancellation, Predicate<String> cancelQueued) {
    this.registry = registry; this.bus = bus; this.events = events;
    this.cancellation = cancellation; this.cancelQueued = cancelQueued;
    subscription = bus == null ? null : bus.subscribe(this::onEvent);
  }
  private Object monitor() { return bus == null ? registry : bus; }
  public RunSnapshot get(String runId) { synchronized (monitor()) { purgeExpired(); return registry.get(runId); } }
  public boolean restored(String runId) { synchronized(monitor()) { return registry.restored(runId); } }
  public void purgeExpired() {
    synchronized (monitor()) {
      var expired = registry.purgeExpired();
      expired.forEach(queuedCancellation::remove);
      if (bus != null) {
        expired.forEach(bus::purgeRun);
        bus.purgeExpired(registry.runIds());
      }
    }
  }
  public record CancellationResult(boolean accepted, RunSnapshot run) {}
  public CancellationResult cancel(String runId) {
    Runnable notify=null;CancellationResult result;
    synchronized (monitor()) {
      purgeExpired();
      var current = registry.get(runId);
      if (!registry.transition(runId, RunStatus.CANCELLED, null)) return new CancellationResult(false, current);
      if (current.status() == RunStatus.QUEUED && cancelQueued.test(runId)) {
        cancellation.forgetPendingCancellation(runId);
        notify=queuedCancellation.remove(runId);
      } else cancellation.cancelRun(runId);
      bus.publish(events.runCancelled(runId, null).withOwnership(current.context()));
      result=new CancellationResult(true, registry.get(runId));
    }
    // Durable application callbacks can acquire their own locks. Never invoke them under the bus monitor.
    notifyCancellation(notify);
    return result;
  }
  private void notifyCancellation(Runnable notify) {
    if(notify!=null)try{notify.run();}catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(RunService.class).warn("Queued cancellation notification failed: {}",error.getClass().getSimpleName());}
  }
  /** Invoked inside the project executor, and returns only after runner cleanup. */
  public void execute(AgentRunContext context, Runnable runner) {
    Runnable skipped;boolean started;
    synchronized (monitor()) {
      skipped=queuedCancellation.remove(context.runId());
      started=registry.transition(context.runId(), RunStatus.RUNNING, null);
      if (!started) {
        cancellation.forgetPendingCancellation(context.runId());
      }
    }
    if(!started){notifyCancellation(skipped);return;}
    try { runner.run(); }
    catch (RuntimeException error) { fail(context, "Runner failed"); }
    finally {
      fail(context, "Runner ended without a terminal event");
      cancellation.forgetPendingCancellation(context.runId());
    }
  }
  private void fail(AgentRunContext context, String message) {
    synchronized (monitor()) {
      var failure = new RunFailure("ExecutionFailure", message);
      if (registry.transition(context.runId(), RunStatus.FAILED, failure))
        bus.publish(events.runFailed(context.runId(), new ErrorInformation(failure.type(), failure.message(), null))
            .withOwnership(context));
    }
  }
  /** For application shortcuts that never entered Chat; transition and event publication are atomic with cancel. */
  public void finishMissingTerminal(AgentRunContext context,RunStatus terminal) {
    if(!terminal.isTerminal())throw new IllegalArgumentException("Terminal state required");
    synchronized(monitor()) {
      var failure=terminal==RunStatus.FAILED?new RunFailure("ExecutionFailure","Application Run failed"):null;
      if(registry.transition(context.runId(),terminal,failure)) {
        var event=switch(terminal) {
          case COMPLETED->events.runCompleted(context.runId(),0);
          case CANCELLED->events.runCancelled(context.runId(),null);
          default->events.runFailed(context.runId(),new ErrorInformation(failure.type(),failure.message(),null));
        };
        bus.publish(event.withOwnership(context));
      }
    }
  }
  private void onEvent(AgentEvent event) {
    if (event.runId() == null) return;
    RunStatus status = switch (event.type()) {
      case AGENT_RUN_COMPLETED -> RunStatus.COMPLETED;
      case AGENT_RUN_FAILED -> RunStatus.FAILED;
      case AGENT_RUN_CANCELLED -> RunStatus.CANCELLED;
      default -> null;
    };
    if (status == null) return;
    try {
      // Do not expose arbitrary exception messages (which may contain credentials) through status polling.
      registry.transition(event.runId(), status, status == RunStatus.FAILED
          ? new RunFailure("ExecutionFailure", "Agent run failed") : null);
    } catch (RunNotFoundException ignored) { /* CLI and auxiliary runs need no Web registry entry. */ }
  }
  @Override public void close() { if (subscription != null) subscription.unsubscribe(); synchronized(monitor()){queuedCancellation.clear();} }
}
