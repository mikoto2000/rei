package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import java.util.function.Predicate;

public class RunService {
  private final RunRegistry registry;
  private final AgentEventBus bus;
  private final AgentEventFactory events;
  private final CommandCancellationService cancellation;
  private final Predicate<String> cancelQueued;
  public RunService(RunRegistry registry) { this(registry, null, null, null, null); }
  public RunService(RunRegistry registry, AgentEventBus bus, AgentEventFactory events,
      CommandCancellationService cancellation, Predicate<String> cancelQueued) {
    this.registry = registry; this.bus = bus; this.events = events;
    this.cancellation = cancellation; this.cancelQueued = cancelQueued;
    if (bus != null) bus.subscribe(this::onEvent);
  }
  private Object monitor() { return bus == null ? registry : bus; }
  public RunSnapshot get(String runId) { synchronized (monitor()) { return registry.get(runId); } }
  public record CancellationResult(boolean accepted, RunSnapshot run) {}
  public CancellationResult cancel(String runId) {
    synchronized (monitor()) {
      var current = registry.get(runId);
      if (!registry.transition(runId, RunStatus.CANCELLED, null)) return new CancellationResult(false, current);
      if (current.status() == RunStatus.QUEUED && cancelQueued.test(runId)) {
        cancellation.forgetPendingCancellation(runId);
      } else cancellation.cancelRun(runId);
      bus.publish(events.runCancelled(runId, null).withOwnership(current.context()));
      return new CancellationResult(true, registry.get(runId));
    }
  }
  /** Invoked inside the project executor, and returns only after runner cleanup. */
  public void execute(AgentRunContext context, Runnable runner) {
    synchronized (monitor()) {
      if (!registry.transition(context.runId(), RunStatus.RUNNING, null)) {
        cancellation.forgetPendingCancellation(context.runId());
        return;
      }
    }
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
}
