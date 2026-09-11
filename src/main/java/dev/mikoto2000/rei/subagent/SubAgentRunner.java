package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import reactor.core.Disposables;
import reactor.core.scheduler.Schedulers;

/** Per-invocation state only. The sole inherited values are explicit task/context, model and project location. */
public final class SubAgentRunner {
  private final SubAgentRegistry registry;
  private final SubAgentToolPolicy policy;
  private final Function<String, ChatModel> models;
  private final Function<String, ToolCallingChatOptions> options;
  private final Supplier<List<ToolCallback>> toolFactory;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private final Clock clock;
  private final ConcurrentMap<String, Runnable> active = new ConcurrentHashMap<>();
  public SubAgentRunner(SubAgentRegistry registry, SubAgentToolPolicy policy, Function<String, ChatModel> models,
      Function<String, ToolCallingChatOptions> options, Supplier<List<ToolCallback>> toolFactory,
      CommandCancellationService cancellation, AgentEventFactory events, AgentEventPublisher publisher, Clock clock) {
    this.registry = registry; this.policy = policy; this.models = models; this.options = options;
    this.toolFactory = toolFactory; this.cancellation = cancellation; this.events = events; this.publisher = publisher; this.clock = clock;
  }
  public boolean cancel(String runId) {
    var operation = active.get(runId);
    if (operation == null) return false;
    operation.run(); return true;
  }
  public SubAgentResult run(String agent, String task, String context) {
    String runId = UUID.randomUUID().toString();
    Instant started = clock.instant();
    long nanos = System.nanoTime();
    var parent = AgentRunScope.current();
    String parentId = parent == null ? null : parent.runId();
    var owner = new AgentRunContext(runId, "subagent:" + runId,
        parent == null ? Path.of(".") : parent.projectRoot(), parent == null ? null : parent.projectId());
    AtomicBoolean stopped = new AtomicBoolean();
    var subscriptions = Disposables.composite();
    CompletableFuture<SubAgentResult> completion = new CompletableFuture<>();
    BiConsumer<SubAgentResult.Status, String> finish = (status, output) -> {
      if (stopped.compareAndSet(false, true)) {
        subscriptions.dispose();
        completion.complete(new SubAgentResult(agent, runId, status, output, started, clock.instant()));
      }
    };
    Runnable cancel = () -> finish.accept(SubAgentResult.Status.CANCELLED, "SubAgent cancelled");
    Runnable check = () -> { if (stopped.get() || Thread.currentThread().isInterrupted()) throw new CancellationException(); };
    active.put(runId, cancel);
    try (var scope = AgentRunScope.open(owner)) {
      publisher.publish(events.subAgentLifecycle(AgentEventType.SUBAGENT_STARTED, parentId, runId, agent, task, "RUNNING", 0, null));
      subscriptions.add(cancellation.onCancel(parentId, cancel));
      var definition = registry.findById(agent);
      if (parent != null && parent.conversationId().startsWith("subagent:")) {
        finish.accept(SubAgentResult.Status.FAILED, "Recursive delegation is prohibited");
      } else if (definition.isEmpty()) {
        finish.accept(SubAgentResult.Status.UNKNOWN_AGENT, "Unknown SubAgent");
      } else if (task == null || task.isBlank()) {
        finish.accept(SubAgentResult.Status.FAILED, "Task is required");
      } else if (!stopped.get()) {
        try {
          var d = definition.get();
          policy.validate(d.requestedTools());
          var effective = policy.effectiveTools(d.requestedTools());
          List<ToolCallback> callbacks = toolFactory.get().stream()
              .filter(callback -> effective.contains(callback.getToolDefinition().name()))
              .map(callback -> guarded(callback, owner, check)).toList();
          if (callbacks.size() != effective.size()) throw new IllegalStateException("Tool unavailable");
          ToolCallingChatOptions runOptions = options.apply(d.model()).copy();
          runOptions.setInternalToolExecutionEnabled(false);
          runOptions.setToolNames(Set.of());
          runOptions.setToolCallbacks(callbacks);
          runOptions.setToolContext(Map.of(AgentRunContext.class.getName(), owner));
          String input = context == null || context.isBlank() ? task : task + "\n\nContext:\n" + context;
          var prompt = new Prompt(List.of(new SystemMessage(d.systemPrompt()), new UserMessage(input)), runOptions);
          ChatModel model = models.apply(d.model());
          ToolLoopSupport.requireNoDefaultTools(model);
          subscriptions.add(new BoundedToolLoop().run(model, prompt, d.maxSteps(), owner, check)
              .subscribeOn(Schedulers.boundedElastic()).timeout(d.timeout())
              .subscribe(output -> finish.accept(SubAgentResult.Status.COMPLETED, output), error -> {
                var status = error instanceof TimeoutException ? SubAgentResult.Status.TIMEOUT
                    : error instanceof BoundedToolLoop.MaxStepsExceeded ? SubAgentResult.Status.MAX_STEPS_EXCEEDED
                    : error instanceof CancellationException ? SubAgentResult.Status.CANCELLED : SubAgentResult.Status.FAILED;
                finish.accept(status, "SubAgent stopped: " + status);
              }));
        } catch (RuntimeException error) { finish.accept(SubAgentResult.Status.FAILED, "SubAgent setup failed"); }
      }
      SubAgentResult result;
      try { result = completion.get(); }
      catch (InterruptedException error) { cancel.run(); Thread.currentThread().interrupt(); result = completion.join(); }
      catch (ExecutionException error) { throw new IllegalStateException(error); }
      publisher.publish(events.subAgentLifecycle(result.status() == SubAgentResult.Status.COMPLETED
          ? AgentEventType.SUBAGENT_COMPLETED : AgentEventType.SUBAGENT_FAILED, parentId, runId, agent, task,
          result.status().name(), (System.nanoTime() - nanos) / 1_000_000,
          result.status() == SubAgentResult.Status.COMPLETED ? null : result.status().name()));
      return result;
    } finally { subscriptions.dispose(); active.remove(runId); }
  }
  private ToolCallback guarded(ToolCallback callback, AgentRunContext owner, Runnable check) {
    // Child tool events use the existing API; lifecycle envelopes provide parent correlation.
    ToolCallback observed = new ToolEventCallbackDecorator(callback, events, publisher);
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() { return callback.getToolDefinition(); }
      public ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
      public String call(String input) { return call(input, new ToolContext(Map.of())); }
      public String call(String input, ToolContext context) {
        try (var scope = AgentRunScope.open(owner)) {
          check.run();
          String result = observed.call(input, context);
          check.run();
          return result;
        }
      }
    };
  }
}
