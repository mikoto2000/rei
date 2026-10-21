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
 * Slackエージェントのユースケースを実現する。
 *
 * <p>Socket pump drives persisted dependencies. Waiting occupies neither a worker nor a model call.
 */
public final class SlackAgentService implements RunContinuation {
  /** 設定。 */
  private final SlackAgentProperties settings;

  /** 要求一覧。 */
  private final SlackAgentRepository requests;

  /** 継続入力一覧。 */
  private final SlackAgentContinuationStore continuations;

  /** 登録台帳。 */
  private final RunRegistry registry;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final ConversationInputRouter router;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 通信経路。 */
  private volatile SlackTransport transport;

  /** self／チーム。 */
  private volatile String self, team;

  /** accepting要求一覧。 */
  private volatile boolean acceptingRequests;

  /** 再開。 */
  private BiFunction<AgentRunContext, Saved, ChatExecutionResult> resume =
      (o, s) -> {
        throw new IllegalStateException("Chat continuation unavailable");
      };

  /** 検証。 */
  private Consumer<AgentRunContext> validate = o -> {};

  /** 管理対象。 */
  private final Map<String, Managed> managed = new java.util.concurrent.ConcurrentHashMap<>();

  /** 完了条件通知一覧。 */
  private final java.util.Queue<Runnable> completionNotifications =
      new java.util.concurrent.ConcurrentLinkedQueue<>();

  /**
   * Slackとの連携で受け渡す管理対象を表す。
   *
   * @param reservation 予約
   * @param completed 完了
   */
  private record Managed(
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {}

  /** 依存条件一覧。 */
  private dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies;

  /** 観測内容一覧。 */
  private dev.mikoto2000.rei.core.dependency.DependencyObservationService observations;

  /** Slackとの連携に関する依存条件一覧を処理する。 */
  public void dependencies(
      dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies,
      dev.mikoto2000.rei.core.dependency.DependencyObservationService observations) {
    this.dependencies = dependencies;
    this.observations = observations;
  }

  /** Slackとの連携に関する登録済みを処理する。 */
  public void registered(SlackAgentRepository.Request q) {
    if (transport == null || !acceptingRequests)
      throw new IllegalStateException(
          "Verified Slack connection required before external request admission");
    if (dependencies == null || requests.dependency(q.id()) != null) return;
    var owner = registry.get(q.run()).context();
    validate.accept(owner);
    // 別のプロジェクトの情報を、この作業の対象として扱わせない。
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

  /** 要求された条件を満たすか判断するため、識別情報一覧を確認する。 */
  public void verifyIdentities(String self, String team, Function<String, String> botId) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
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

  /** 指定された値や利用先を使って、SlackAgentServiceを初期化する。 */
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

  /** 通知に応じて状態や表示を更新するため、再開を受け付ける。 */
  public void onResume(BiFunction<AgentRunContext, Saved, ChatExecutionResult> executor) {
    resume = executor;
  }

  /** 通知に応じて状態や表示を更新するため、検証を受け付ける。 */
  public void onValidate(Consumer<AgentRunContext> validate) {
    this.validate = validate;
  }

  /** Slackとの連携で用いる予約を返す。 */
  public OutputLimitRunBudget.LlmCallReservation reservation(String run) {
    var m = managed.get(run);
    return m == null ? null : m.reservation();
  }

  /** Slackとの連携に関する関連付け親を処理する。 */
  @Override
  public void attachParent(
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      Consumer<ChatExecutionResult> completed) {
    managed.put(owner.runId(), new Managed(reservation, completed));
  }

  /** Slackとの連携に関する関連付けの解除親を処理する。 */
  @Override
  public void detachParent(AgentRunContext owner) {
    managed.remove(owner.runId());
  }

  /** Slackとの連携に関する関連付けを処理する。 */
  public void attach(String self, String team, SlackTransport transport) {
    attach(self, team, transport, true);
  }

  /** Slackとの連携に関する関連付けを処理する。 */
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

  /** Slackとの連携に関する有効化要求一覧を処理する。 */
  public void enableRequests() {
    if (transport == null) throw new IllegalStateException("Slack transport unavailable");
    acceptingRequests = true;
  }

  /** Slackとの連携に関する関連付けの解除を処理する。 */
  public void detach() {
    acceptingRequests = false;
    transport = null;
  }

  /** 処理を継続できる状態に戻すため、Slackエージェントサービスを復旧する。 */
  public void recover() {
    requests.recover();
    continuations.recover();
  }

  /**
   * Slackとの連携において、応答担当Senderかどうかを判定する。
   *
   * <p>Classification only: acceptance still requires exact Bot/Workspace/Channel/Thread/state
   * checks.
   */
  public boolean isBotSender(String user) {
    if (user == null) return false;
    if (user.equals(self)) return true;
    return settings.getAgents() != null
        && settings.getAgents().values().stream()
            .filter(Objects::nonNull)
            .anyMatch(agent -> user.equals(agent.botUserId()));
  }

  /** Slackとの連携において、受信に該当するか判定する。 */
  public boolean receive(SlackMessage message) {
    return message != null
        && transport != null
        && Objects.equals(team, message.team())
        && requests.receive(message, self, settings);
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
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

  /** Slackとの連携において、中断に該当するか判定する。 */
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

  /** Slackとの連携に関する終端を処理する。 */
  @Override
  public void terminal(AgentRunContext owner) {
    continuations.complete(owner.runId());
  }

  /** 要求を後続の処理へ引き渡すため、案内を受け付ける。 */
  @Override
  public void acceptGuidance(AgentRunContext owner, List<Message> guidance) {
    continuations.appendGuidance(owner, guidance);
  }

  /** 対象の実行をキャンセルする。 */
  public void cancel(String run) {
    requests.cancelRun(run);
    continuations.cancel(run);
    updateDependencies(run);
    var m = managed.remove(run);
    if (m != null)
      completionNotifications.add(() -> m.completed().accept(ChatExecutionResult.cancelled()));
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
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

  /** Slackとの連携に関するpumpを処理する。 */
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

  /** 処理の継続を終えるため、不明を停止する。 */
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

  /** 最新の内容を後続の処理に反映するため、依存条件一覧を更新する。 */
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

  /** Slackとの連携で用いる特殊文字の保護を返す。 */
  private static String escape(String text) {
    return SlackReplies.safe(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
