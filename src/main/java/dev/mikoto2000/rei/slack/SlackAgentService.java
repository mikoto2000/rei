package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatExecutionResult;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Socket pump drives persisted dependencies. Waiting occupies neither a worker nor a model call.
 */
public final class SlackAgentService implements RunContinuation {
  private final SlackAgentProperties settings;
  private final SlackAgentRepository requests;
  private final SlackAgentContinuationStore continuations;
  private final RunRegistry registry;
  private final RunService runs;
  private final ConversationInputRouter router;
  private final Clock clock;
  private volatile SlackTransport transport;
  private volatile String self, team;
  private volatile boolean acceptingRequests;
  private BiFunction<AgentRunContext, Saved, ChatExecutionResult> resume =
      (o, s) -> {
        throw new IllegalStateException("Chat continuation unavailable");
      };
  private Consumer<AgentRunContext> validate = o -> {};
  private final Map<String, Managed> managed = new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.Queue<Runnable> completionNotifications =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  private record Managed(
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {}

  private dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies;
  private dev.mikoto2000.rei.core.dependency.DependencyObservationService observations;

  public void dependencies(
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      dev.mikoto2000.rei.core.dependency.DependencyObservationService observations) {
    this.dependencies = dependencies;
    this.observations = observations;
  }

  public void registered(SlackAgentRepository.Request q) {
    if (transport == null || !acceptingRequests)
      throw new IllegalStateException(
          "Verified Slack connection required before external request admission");
    if (dependencies == null || requests.dependency(q.id()) != null) return;
    var owner = registry.get(q.run()).context();
    validate.accept(owner);
    if (!owner.projectId().equals(q.project())
        || !owner.conversationId().equals(q.session())
        || !owner.projectRoot().toString().equals(q.root()))
      throw new IllegalArgumentException("External dependency ownership mismatch");
    var dependency =
        dependencies.create(
            owner,
            new dev.mikoto2000.rei.core.dependency.DependencySpec(
                dev.mikoto2000.rei.core.dependency.DependencySpec.Kind.EXTERNAL_AGENT,
                q.id(),
                null),
            java.time.Duration.ofMillis(Math.max(1000, q.deadline() - clock.millis())),
            List.of());
    requests.dependency(q.id(), dependency.id());
  }

  public void verifyIdentities(String self, String team, Function<String, String> botId) {
    if (settings.getAgents().isEmpty() || settings.getAgents().size() > 16)
      throw new IllegalArgumentException("Explicit external agents required");
    for (String name : settings.getAgents().keySet()) {
      var a = settings.agent(name);
      if (!a.workspaceId().equals(team)
          || a.botUserId().equals(self)
          || !a.botId().equals(botId.apply(a.botUserId())))
        throw new IllegalArgumentException("External Bot/User identity validation failed");
    }
  }

  public SlackAgentService(
      SlackAgentProperties settings,
      SlackAgentRepository requests,
      SlackAgentContinuationStore continuations,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      Clock clock) {
    this.settings = settings;
    this.requests = requests;
    this.continuations = continuations;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.clock = clock;
  }

  public void onResume(BiFunction<AgentRunContext, Saved, ChatExecutionResult> executor) {
    resume = executor;
  }

  public void onValidate(Consumer<AgentRunContext> validate) {
    this.validate = validate;
  }

  public OutputLimitRunBudget.LlmCallReservation reservation(String run) {
    var m = managed.get(run);
    return m == null ? null : m.reservation();
  }

  @Override
  public void attachParent(
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {
    managed.put(owner.runId(), new Managed(reservation, completed));
  }

  @Override
  public void detachParent(AgentRunContext owner) {
    managed.remove(owner.runId());
  }

  public void attach(String self, String team, SlackTransport transport) {
    attach(self, team, transport, true);
  }

  public void attach(
      String self, String team, SlackTransport transport, boolean acceptingRequests) {
    for (String name : settings.getAgents().keySet()) {
      var a = settings.agent(name);
      if (!a.workspaceId().equals(team) || a.botUserId().equals(self))
        throw new IllegalArgumentException("External agent identity outside this installation");
    }
    this.self = self;
    this.team = team;
    this.transport = transport;
    this.acceptingRequests = acceptingRequests;
  }

  public void enableRequests() {
    if (transport == null) throw new IllegalStateException("Slack transport unavailable");
    acceptingRequests = true;
  }

  public void detach() {
    acceptingRequests = false;
    transport = null;
  }

  public void recover() {
    requests.recover();
    continuations.recover();
  }

  /**
   * Classification only: acceptance still requires exact Bot/Workspace/Channel/Thread/state checks.
   */
  public boolean isBotSender(String user) {
    if (user == null) return false;
    if (user.equals(self)) return true;
    return settings.getAgents() != null
        && settings.getAgents().values().stream()
            .filter(Objects::nonNull)
            .anyMatch(agent -> user.equals(agent.botUserId()));
  }

  public boolean receive(SlackMessage message) {
    return message != null
        && transport != null
        && Objects.equals(team, message.team())
        && requests.receive(message, self, settings);
  }

  @Override
  public Saved restore(AgentRunContext owner) {
    if (!"RESUMING".equals(continuations.state(owner.runId()))) return null;
    var s = continuations.get(owner);
    var byCall = new HashMap<String, SlackAgentRepository.Request>();
    requests.forRun(owner.runId()).forEach(q -> byCall.put(q.call(), q));
    var messages =
        s.messages().stream()
            .map(
                m -> {
                  if (!(m instanceof ToolResponseMessage tool)) return m;
                  return (Message)
                      ToolResponseMessage.builder()
                          .responses(
                              tool.getResponses().stream()
                                  .map(
                                      r -> {
                                        var q = byCall.get(r.id());
                                        return q != null && r.name().equals("askExternalAgent")
                                            ? new ToolResponseMessage.ToolResponse(
                                                r.id(), r.name(), requests.result(q.id()))
                                            : r;
                                      })
                                  .toList())
                          .build();
                })
            .toList();
    return new Saved(s.request(), messages, s.budget());
  }

  @Override
  public boolean suspend(RunExecutionContext e, List<Message> history) {
    if (history.isEmpty()
        || !(history.getLast() instanceof ToolResponseMessage tool)
        || tool.getResponses().stream().noneMatch(r -> r.name().equals("askExternalAgent")))
      return false;
    var owner = e.runContext();
    validate.accept(owner);
    var current = registry.get(owner.runId());
    if (!current.context().equals(owner)
        || current.status() != RunStatus.RUNNING
        || !registry.ownsExecution(owner.runId()))
      throw new IllegalStateException("External wait lost parent ownership");
    if (e.hasParentReservation() && !managed.containsKey(owner.runId()))
      throw new IllegalStateException("Managed parent continuation required to retain Goal budget");
    continuations.save(
        owner,
        e.userRequest(),
        history,
        e.budgetSnapshot(),
        managed.containsKey(owner.runId()) || e.hasParentReservation());
    if (!registry.transition(owner.runId(), RunStatus.WAITING, null)) {
      continuations.cancel(owner.runId());
      requests.cancelRun(owner.runId());
      throw new java.util.concurrent.CancellationException();
    }
    return true;
  }

  @Override
  public void terminal(AgentRunContext owner) {
    continuations.complete(owner.runId());
  }

  @Override
  public void acceptGuidance(AgentRunContext owner, List<Message> guidance) {
    continuations.appendGuidance(owner, guidance);
  }

  public void cancel(String run) {
    requests.cancelRun(run);
    continuations.cancel(run);
    updateDependencies(run);
    var m = managed.remove(run);
    if (m != null)
      completionNotifications.add(() -> m.completed().accept(ChatExecutionResult.cancelled()));
  }

  public void onEvent(AgentEvent event) {
    if (event.type() == AgentEventType.AGENT_RUN_CANCELLED && event.runId() != null) {
      try {
        var o = registry.get(event.runId()).context();
        if (Objects.equals(event.projectId(), o.projectId())
            && Objects.equals(event.sessionId(), o.conversationId())) cancel(event.runId());
      } catch (RunNotFoundException ignored) {
      }
    }
  }

  public synchronized void pump() {
    // Cancellation events arrive under the event-bus monitor; never invoke Goal/Scheduler callbacks
    // there.
    Runnable notification;
    while ((notification = completionNotifications.poll()) != null) notification.run();
    if (dependencies != null)
      for (var q : requests.active()) {
        String id = requests.dependency(q.id());
        if (id != null
            && dependencies.get(q.project(), id).state()
                == dev.mikoto2000.rei.core.dependency.DependencyState.CANCELLED)
          requests.cancelRequest(q.id());
      }
    requests.expire(clock.instant());
    var sender = transport;
    if (sender != null) {
      var attempted = new HashSet<String>();
      for (var q : requests.pending()) {
        String channel = q.team() + ":" + q.channel();
        if (!attempted.add(channel)) continue;
        SlackAgentProperties.Agent a;
        try {
          a = settings.agent(q.agent());
        } catch (IllegalArgumentException invalid) {
          requests.cancelRun(q.run());
          continue;
        }
        if (!Objects.equals(team, q.team())
            || !a.workspaceId().equals(q.team())
            || !a.channelId().equals(q.channel())
            || !a.botId().equals(q.bot())
            || !a.botUserId().equals(q.botUser())
            || a.botUserId().equals(self)) continue;
        var parent = registry.get(q.run());
        if (!parent.context().projectId().equals(q.project())
            || !parent.context().conversationId().equals(q.session())
            || !parent.context().projectRoot().toString().equals(q.root())
            || parent.status().isTerminal()) {
          requests.cancelRun(q.run());
          continue;
        }
        if (parent.status() == RunStatus.WAITING && !registry.claimWaiting(parent.context()))
          continue;
        if (!registry.ownsExecution(q.run())) continue;
        if ("WAITING".equals(continuations.state(q.run()))
            && continuations.document(q.run()).managedParent()
            && !managed.containsKey(q.run())) {
          requests.abandonCreated(q.run());
          stopUnknown(parent.context());
          continue;
        }
        try {
          validate.accept(parent.context());
        } catch (RuntimeException invalid) {
          requests.abandonCreated(q.run());
          continue;
        }
        if (!requests.claimSend(q.id())) continue;
        String text =
            "<@"
                + q.mention()
                + "> "
                + escape(q.question())
                + "\nExpected output: "
                + escape(q.expected())
                + "\nRequest: "
                + q.id();
        SlackTransport.Delivery result;
        try {
          result = sender.send(new SlackStore.Outgoing(q.id(), q.team(), q.channel(), null, text));
        } catch (RuntimeException error) {
          result = SlackTransport.Delivery.unknown();
        }
        requests.delivery(q.id(), result);
      }
    }
    if (dependencies != null)
      for (String run : requests.terminalDependencyRuns()) updateDependencies(run);
    for (String run : continuations.waiting()) {
      var current = registry.get(run);
      var owner = current.context();
      if (current.status() == RunStatus.CANCELLED) {
        cancel(run);
        continue;
      }
      if (current.status() != RunStatus.WAITING || router.containsRun(owner.projectId(), run))
        continue;
      if (continuations.document(run).managedParent() && !managed.containsKey(run)) {
        stopUnknown(owner);
        continue;
      }
      try {
        validate.accept(owner);
      } catch (RuntimeException invalid) {
        stopUnknown(owner);
        continue;
      }
      if (requests.waiting(run)) continue;
      updateDependencies(run);
      if (!registry.claimWaiting(owner) || !continuations.claim(run)) continue;
      var saved = restore(owner);
      try {
        router.submitOperation(
            owner,
            () -> {
              ChatExecutionResult result;
              try {
                validate.accept(owner);
                result = resume.apply(owner, saved);
              } catch (RuntimeException uncertain) {
                continuations.unknown(run);
                runs.finishMissingTerminal(owner, RunStatus.UNKNOWN);
                var parent = managed.remove(run);
                if (parent != null) parent.completed().accept(ChatExecutionResult.cancelled());
                return;
              }
              if (result.status() != ChatExecutionResult.Status.WAITING) {
                runs.finishMissingTerminal(
                    owner,
                    result.status() == ChatExecutionResult.Status.CANCELLED
                        ? RunStatus.CANCELLED
                        : result.success() ? RunStatus.COMPLETED : RunStatus.FAILED);
                continuations.complete(run);
                var parent = managed.remove(run);
                if (parent != null) parent.completed().accept(result);
              }
            },
            work -> runs.execute(owner, work));
      } catch (RuntimeException uncertain) {
        continuations.unknown(run);
        runs.finishMissingTerminal(owner, RunStatus.UNKNOWN);
      }
    }
    if (observations != null) observations.flushFacts();
  }

  private void stopUnknown(AgentRunContext owner) {
    if (!registry.claimWaiting(owner)) return;
    continuations.unknown(owner.runId());
    runs.finishMissingTerminal(owner, RunStatus.UNKNOWN);
    var parent = managed.remove(owner.runId());
    if (parent != null)
      completionNotifications.add(
          () ->
              parent
                  .completed()
                  .accept(
                      ChatExecutionResult.failed(
                          "External continuation owner unavailable", "external_owner_unknown")));
  }

  private void updateDependencies(String run) {
    if (dependencies == null) return;
    for (var q : requests.forRun(run)) {
      String id = requests.dependency(q.id());
      if (id == null) continue;
      var dependency = dependencies.get(q.project(), id);
      var state =
          switch (q.state()) {
            case "COMPLETED" -> dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED;
            case "CANCELLED" -> dev.mikoto2000.rei.core.dependency.DependencyState.CANCELLED;
            case "FAILED", "TIMED_OUT", "UNKNOWN" ->
                dev.mikoto2000.rei.core.dependency.DependencyState.FAILED;
            default -> dev.mikoto2000.rei.core.dependency.DependencyState.WAITING;
          };
      dependencies.observe(dependency, state, "external_" + q.state().toLowerCase(Locale.ROOT));
    }
  }

  private static String escape(String text) {
    return SlackReplies.safe(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
