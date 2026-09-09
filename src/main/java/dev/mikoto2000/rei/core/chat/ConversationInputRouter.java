package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;

/** Dispatches immutable requests; the Shell never owns an executing command object. */
public final class ConversationInputRouter {
  public enum Disposition { STARTED, QUEUED }
  @FunctionalInterface public interface Runner {
    void execute(AgentRunContext context, String prompt, UserInterventionQueue queue);
  }
  private final Executor executor;
  private final Runner runner;
  private final java.util.function.BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received;
  private final java.util.concurrent.ConcurrentMap<String, Slot> active = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final class Slot {
    final AgentRunContext context;
    final String prompt;
    final UserInterventionQueue queue;
    ActiveRun view;
    Slot next; // Accessed only in the per-project atomic compute operation.
    Slot(AgentRunContext context, String prompt, UserInterventionQueue queue) {
      this.context = context; this.prompt = prompt; this.queue = queue;
      this.view = ActiveRun.of(context, prompt);
    }
  }
  @FunctionalInterface public interface Subscription extends AutoCloseable { void close(); }
  public Subscription onChange(Runnable listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }
  public List<ActiveRun> activeRuns() {
    return active.values().stream().map(slot -> slot.view)
        .sorted(Comparator.comparing(ActiveRun::startedAt).thenComparing(ActiveRun::runId)).toList();
  }

  public ConversationInputRouter(Executor executor, Runner runner) {
    this(executor, runner, (context, entry) -> {});
  }
  public ConversationInputRouter(Executor executor, Runner runner,
      java.util.function.BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received) {
    this.executor = executor;
    this.runner = runner;
    this.received = received;
  }

  public Disposition submit(Path root, String conversation, String prompt) {
    Path location = root.toAbsolutePath().normalize();
    String projectId = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversation);
    String key = projectId == null ? location.toString() : projectId;
    var launch = new java.util.concurrent.atomic.AtomicReference<Slot>();
    var disposition = new java.util.concurrent.atomic.AtomicReference<>(Disposition.QUEUED);
    active.compute(key, (ignored, existing) -> {
      if (existing != null) {
        if (existing.next != null) { existing.next.queue.offer(prompt); return existing; }
        if (existing.queue.offer(prompt)) return existing;
        // The mailbox is closed but its runner is still unwinding. Start the successor only after finally.
        existing.next = slot(location, conversation, projectId, prompt);
        disposition.set(Disposition.STARTED);
        return existing;
      }
      var created = slot(location, conversation, projectId, prompt);
      launch.set(created);
      disposition.set(Disposition.STARTED);
      return created;
    });
    if (launch.get() != null) { changed(); dispatch(key, launch.get()); }
    return disposition.get();
  }

  private Slot slot(Path root, String conversation, String projectId, String prompt) {
    var context = new AgentRunContext(UUID.randomUUID().toString(), conversation, root, projectId);
    return new Slot(context, prompt, new UserInterventionQueue(entry -> received.accept(context, entry)));
  }
  private void dispatch(String key, Slot slot) {
    try {
      executor.execute(() -> {
        try { runner.execute(slot.context, slot.prompt, slot.queue); }
        finally { complete(key, slot); }
      });
    } catch (RuntimeException error) {
      complete(key, slot);
      throw error;
    }
  }
  private void complete(String key, Slot slot) {
    var successor = new java.util.concurrent.atomic.AtomicReference<Slot>();
    active.computeIfPresent(key, (ignored, current) -> {
      if (current != slot) return current;
      successor.set(current.next);
      if (current.next != null) current.next.view = ActiveRun.of(current.next.context, current.next.prompt);
      return current.next;
    });
    changed();
    if (successor.get() != null) dispatch(key, successor.get());
  }
  private void changed() {
    for (var listener : listeners) try { listener.run(); }
    catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(ConversationInputRouter.class).warn("Active run display listener failed", error);
    }
  }
}
