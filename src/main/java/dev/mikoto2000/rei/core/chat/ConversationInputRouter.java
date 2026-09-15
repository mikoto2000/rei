package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;
import dev.mikoto2000.rei.core.execution.*;
import dev.mikoto2000.rei.core.project.ProjectContext;

/** Owns input mailboxes and active views; ProjectRunQueue owns all AGENT scheduling. */
public final class ConversationInputRouter {
  public enum Disposition { STARTED, QUEUED }
  @FunctionalInterface public interface Runner {
    void execute(AgentRunContext context, String prompt, UserInterventionQueue queue);
  }
  @FunctionalInterface public interface Subscription extends AutoCloseable { void close(); }
  private final Executor executor;
  private final Runner runner;
  private final ProjectRunQueue projectQueue;
  private final BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received;
  private final Map<String, Slot> mailboxes = new HashMap<>();
  private final Map<String, Slot> agentSlots = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, ActiveRun> active = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, ActiveExecution> background = new java.util.concurrent.ConcurrentHashMap<>();
  private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
  private record Slot(AgentRunContext context, String prompt, UserInterventionQueue queue) {}

  public ConversationInputRouter(Executor executor, Runner runner) { this(executor, runner, (c, e) -> {}); }
  public ConversationInputRouter(Executor executor, Runner runner,
      BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received) {
    this.executor = executor; this.runner = runner; this.received = received;
    this.projectQueue = new ProjectRunQueue(executor);
  }
  public Subscription onChange(Runnable listener) {
    listeners.add(listener); return () -> listeners.remove(listener);
  }
  public List<ActiveRun> activeRuns() {
    return active.values().stream().sorted(Comparator.comparing(ActiveRun::startedAt).thenComparing(ActiveRun::runId)).toList();
  }
  public List<ActiveExecution> activeExecutions() {
    var result = new ArrayList<>(background.values());
    active.values().forEach(run -> result.add(ActiveExecution.fromAgent(run)));
    return result.stream().sorted(Comparator.comparing(ActiveExecution::startedAt).thenComparing(ActiveExecution::id)).toList();
  }
  public void executeAuxiliary(Runnable work) { executor.execute(work); }
  public ActiveExecution submitBackground(ProjectContext project, ExecutionType type, String summary,
      Consumer<ActiveExecution> work) { return submitBackground(project, type, summary, e -> {}, work); }
  public ActiveExecution submitBackground(ProjectContext project, ExecutionType type, String summary,
      Consumer<ActiveExecution> registered, Consumer<ActiveExecution> work) {
    if (type == ExecutionType.AGENT) throw new IllegalArgumentException("Agent input must use its intervention router");
    var execution = new ActiveExecution(UUID.randomUUID().toString(), project.id(),
        project.conversationId(dev.mikoto2000.rei.llm.ConversationIds.chat()), project.root(), type,
        ActiveRun.summary(summary), java.time.Instant.now());
    background.put(execution.id(), execution); changed();
    try {
      registered.accept(execution);
      executor.execute(() -> {
        try (var scope = ExecutionScope.open(execution)) { work.accept(execution); }
        finally { background.remove(execution.id()); changed(); }
      });
    } catch (RuntimeException error) { background.remove(execution.id()); changed(); throw error; }
    return execution;
  }
  public Disposition submit(Path root, String conversation, String prompt) {
    Slot created;
    synchronized (mailboxes) {
      var existing = mailboxes.get(conversation);
      if (existing != null && existing.queue().offer(prompt)) return Disposition.QUEUED;
      created = slot(cliContext(root, conversation), prompt);
      mailboxes.put(conversation, created);
    }
    enqueue(created, Runnable::run);
    return Disposition.STARTED;
  }
  public Disposition submitNewRun(Path root, String conversation, String prompt) {
    return submit(cliContext(root, conversation), prompt);
  }
  public Disposition submit(AgentRunContext context, String prompt) {
    return submit(context, prompt, Runnable::run);
  }
  /** Application lifecycle wraps the runner without reassigning its identity or enqueueing twice. */
  public Disposition submit(AgentRunContext context, String prompt, Consumer<Runnable> lifecycle) {
    return enqueue(slot(context, prompt), lifecycle);
  }
  public boolean cancelQueued(String runId) {
    boolean removed = projectQueue.cancelQueued(runId);
    if (removed) {
      var slot = agentSlots.remove(runId);
      if (slot != null) forget(slot);
    }
    return removed;
  }
  private AgentRunContext cliContext(Path root, String conversation) {
    return new AgentRunContext(UUID.randomUUID().toString(), conversation, root,
        dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversation));
  }
  private Slot slot(AgentRunContext context, String prompt) {
    return new Slot(context, prompt, new UserInterventionQueue(entry -> received.accept(context, entry)));
  }
  private Disposition enqueue(Slot slot, Consumer<Runnable> lifecycle) {
    var context = slot.context();
    agentSlots.put(context.runId(), slot);
    String key = context.projectId() == null ? context.projectRoot().toString() : context.projectId();
    try {
      boolean first = projectQueue.enqueue(key, context.runId(), () -> {
        try { lifecycle.accept(() -> runner.execute(context, slot.prompt(), slot.queue())); }
        finally { agentSlots.remove(context.runId()); forget(slot); }
      }, () -> { active.put(context.runId(), ActiveRun.of(context, slot.prompt())); changed(); },
          () -> { agentSlots.remove(context.runId()); forget(slot); },
          error -> {
            try { lifecycle.accept(() -> { throw error; }); }
            finally { agentSlots.remove(context.runId()); forget(slot); }
          });
      return first ? Disposition.STARTED : Disposition.QUEUED;
    } catch (RuntimeException error) { agentSlots.remove(context.runId()); forget(slot); throw error; }
  }
  private void forget(Slot slot) {
    active.remove(slot.context().runId());
    synchronized (mailboxes) { mailboxes.remove(slot.context().conversationId(), slot); }
    changed();
  }
  private void changed() {
    for (var listener : listeners) try { listener.run(); }
    catch (RuntimeException error) {
      org.slf4j.LoggerFactory.getLogger(ConversationInputRouter.class).warn("Active run display listener failed", error);
    }
  }
}
