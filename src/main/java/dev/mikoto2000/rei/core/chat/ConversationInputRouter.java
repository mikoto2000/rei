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
  private final Map<String, UserInterventionQueue> active = new HashMap<>();

  public ConversationInputRouter(Executor executor, Runner runner) {
    this(executor, runner, (context, entry) -> {});
  }
  public ConversationInputRouter(Executor executor, Runner runner,
      java.util.function.BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received) {
    this.executor = executor;
    this.runner = runner;
    this.received = received;
  }

  public synchronized Disposition submit(Path root, String conversation, String prompt) {
    Path location = root.toAbsolutePath().normalize();
    String projectId = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversation);
    String key = projectId == null ? location.toString() : projectId;
    var existing = active.get(key);
    if (existing != null && existing.offer(prompt)) return Disposition.QUEUED;
    var context = new AgentRunContext(UUID.randomUUID().toString(), conversation, location, projectId);
    var queue = new UserInterventionQueue(entry -> received.accept(context, entry));
    active.put(key, queue);
    try {
      executor.execute(() -> {
        try { runner.execute(context, prompt, queue); }
        finally {
          synchronized (this) { active.remove(key, queue); }
        }
      });
    } catch (RuntimeException error) {
      active.remove(key, queue);
      throw error;
    }
    return Disposition.STARTED;
  }
}
