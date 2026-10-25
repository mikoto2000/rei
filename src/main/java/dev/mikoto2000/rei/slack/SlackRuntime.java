package dev.mikoto2000.rei.slack;

import dev.mikoto2000.rei.application.run.RunNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.ProjectRunQueue;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;

/**
 * Slackとの連携に必要なSlack実行環境を扱う。
 *
 * <p>Channel adapter over the existing Session, Run, Tool and Planning Loop.
 */
public final class SlackRuntime {
  /** この機能の動作設定。 */
  private final SlackProperties p;

  /** 保存先。 */
  private final SlackStore store;

  /** 自分自身の識別情報。 */
  private final String self;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionLifecycle sessions;

  /** 登録台帳。 */
  private final RunRegistry registry;

  /** 実行一覧。 */
  private final RunService runs;

  /** 入力の振り分け。 */
  private final ConversationInputRouter router;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 完了回答。 */
  private java.util.function.BiFunction<String, String, String> completedAnswer =
      (session, run) -> null;

  /** 通知に応じて状態や表示を更新するため、完了回答を受け付ける。 */
  public void onCompletedAnswer(java.util.function.BiFunction<String, String, String> answer) {
    completedAnswer = Objects.requireNonNull(answer);
  }

  /** 指定された値や利用先を使って、SlackRuntimeを初期化する。 */
  public SlackRuntime(
      SlackProperties p,
      SlackStore store,
      String self,
      ProjectRegistry projects,
      SessionLifecycle sessions,
      RunRegistry registry,
      RunService runs,
      ConversationInputRouter router,
      Clock clock) {
    this.p = p;
    this.store = store;
    this.self = self;
    this.projects = projects;
    this.sessions = sessions;
    this.registry = registry;
    this.runs = runs;
    this.router = router;
    this.clock = clock;
  }

  /**
   * 要求を後続の処理へ引き渡すため、Slack実行環境を受け付ける。
   *
   * <p>Called before ACK. No LLM, send or queue execution runs on this thread.
   */
  public boolean accept(SlackMessage m) {
    if (!SlackAdmission.accept(p, self, m, store.knownThread(m))) return false;
    var safe =
        new SlackMessage(
            m.eventId(),
            m.team(),
            m.channel(),
            m.ts(),
            m.threadTs(),
            m.user(),
            m.botId(),
            m.subtype(),
            m.type(),
            m.channelType(),
            SlackReplies.safe(m.text()));
    return store.accept(safe);
  }

  /** Slackとの連携に関する定期確認を処理する。 */
  public void tick() {
    for (var receipt : store.pending()) {
      var m = receipt.message();
      if (!SlackAdmission.accept(p, self, m, store.thread(m).isPresent())) {
        store.state(m, "REJECTED");
        continue;
      }
      try {
        dispatch(m);
      } catch (RuntimeException error) {
        // A saved dispatch fence is never cleared: failure may follow enqueue success.
        store.state(m, "UNKNOWN");
        reply(m.key() + ":failed", m, "受付後の実行結果を確認できません。既存 UI の Run／チェックポイントを確認してください。");
      }
    }
    for (var receipt : store.active()) {
      try {
        var run = runs.get(receipt.run());
        boolean occupied = router.containsRun(receipt.project(), receipt.run());
        if (occupied) continue;
        // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
        if (!run.status().isTerminal()) {
          if (run.status() == RunStatus.WAITING)
            reply(
                receipt.run() + ":external-wait",
                receipt.message(),
                "外部エージェントの返信待ちです。rei status／rei cancel で確認・キャンセルできます。");
          else if (runs.restored(receipt.run()))
            store.notifyRun(
                receipt.run(),
                "UNKNOWN",
                receipt.run() + ":unknown",
                receipt.message(),
                SlackReplies.parts("再起動前の Run の結果は不明です。チェックポイントを確認して明示的に再開してください。"));
          continue;
        }
        if ("WAITING_APPROVAL".equals(receipt.state())) continue;
        String text =
            switch (run.status()) {
              case COMPLETED -> {
                String answer = completedAnswer.apply(receipt.session(), receipt.run());
                yield answer == null || answer.isBlank() ? "完了しました。" : "完了しました。\n\n" + answer;
              }
              case CANCELLED -> "キャンセル完了：Run の実行が停止しました。";
              case FAILED -> "Run が失敗しました。認可された UI で診断を確認してください。";
              default -> "Run の結果が不明です。チェックポイントを確認してください。";
            };
        store.notifyRun(
            receipt.run(),
            run.status().name(),
            receipt.run() + ":terminal",
            receipt.message(),
            SlackReplies.parts(text));
      } catch (RunNotFoundException missing) {
        store.notifyRun(
            receipt.run(),
            "UNKNOWN",
            receipt.run() + ":unknown",
            receipt.message(),
            SlackReplies.parts("Run の保存期間が終了、または実行結果が不明です。履歴とチェックポイントを確認してください。"));
      }
    }
  }

  /** Slackとの連携に関する実行先への引き渡しを処理する。 */
  private void dispatch(SlackMessage m) {
    String text = m.text().replace("<@" + self + ">", "").strip();
    String command =
        switch (text) {
          case "rei status" -> "/rei-status";
          case "rei cancel" -> "/rei-cancel";
          default -> text;
        };
    if (command.equals("/rei-status") || command.equals("/rei-cancel")) {
      control(m, command);
      return;
    }
    if (text.isBlank()) {
      store.state(m, "REJECTED");
      return;
    }
    var binding =
        store
            .thread(m)
            .orElseGet(
                () -> {
                  String id = m.dm() ? p.getDmProjectId() : p.getChannelProjects().get(m.channel());
                  var project =
                      projects
                          .resolveById(id)
                          .orElseThrow(
                              () ->
                                  new IllegalArgumentException("Slack Project is not registered"));
                  return store.bind(
                      m, id, sessions.create(project, "Slack conversation").sessionId());
                });
    var project = projects.resolveById(binding.project()).orElseThrow();
    var accepted =
        sessions.submit(
            project,
            binding.session(),
            text,
            AgentRunContext.RequestSource.WEB,
            context -> {
              if (!store.prepare(m, context.runId(), binding))
                throw new IllegalStateException("Slack receipt already dispatched");
              registry.register(context);
              try {
                reply(context.runId() + ":accepted", m, "受付しました。Run: " + context.runId());
                router.submit(context, text, work -> runs.execute(context, work));
              } catch (ProjectRunQueue.CapacityExceededException rejected) {
                // Definitive rejection: only the existing Run lifecycle executes, with no chat/tool
                // work.
                runs.execute(
                    context,
                    () -> {
                      throw new IllegalStateException("Run admission capacity reached");
                    });
              } catch (RuntimeException uncertain) {
                if (!router.containsRun(context.projectId(), context.runId())) {
                  runs.execute(
                      context, () -> runs.finishMissingTerminal(context, RunStatus.UNKNOWN));
                  store.stateRun(context.runId(), "UNKNOWN");
                }
                throw uncertain;
              }
            });
    store.activateRun(accepted.runId());
  }

  /** Slackとの連携に関する制御を処理する。 */
  private void control(SlackMessage m, String text) {
    // Claim the command before a cancellation side effect, including across restarts.
    if (!store.transition(m, "ACCEPTED", "CONTROL")) return;
    var latest = store.latest(m);
    if (latest.isEmpty()) {
      reply(m.key() + ":control", m, "このスレッドには Run がありません。");
      return;
    }
    var receipt = latest.get();
    var run = runs.get(receipt.run());
    if (text.equals("/rei-status")) {
      String status =
          switch (receipt.state()) {
            case "WAITING_APPROVAL" -> "承認待ち";
            case "CANCEL_REQUESTED" -> "キャンセル受付（停止確認待ち）";
            case "UNKNOWN" -> "結果不明";
            default -> run.status().name();
          };
      reply(m.key() + ":control", m, "Run: " + receipt.run() + "\n状態: " + status);
      return;
    }
    // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
    if (run.status().isTerminal()) {
      reply(m.key() + ":control", m, "Run は終了済みです。");
      return;
    }
    store.stateRun(receipt.run(), "CANCEL_REQUESTED");
    var result = runs.cancel(receipt.run());
    reply(
        m.key() + ":control",
        m,
        result.accepted()
            ? "キャンセルを受け付けました。停止を確認しています。"
            : result.run().status().isTerminal() ? "キャンセル対象は終了済みです。" : "既に停止要求済みです。実行の終了を確認しています。");
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  public void onEvent(AgentEvent event) {
    if (event.runId() == null || event.projectId() == null || event.sessionId() == null) return;
    var receipt = store.byRun(event.runId());
    var target =
        receipt
            .map(SlackStore.Receipt::message)
            .or(() -> store.forSession(event.projectId(), event.sessionId()));
    if (target.isEmpty()) return;
    if (receipt.isPresent()
        && (!event.projectId().equals(receipt.get().project())
            || !event.sessionId().equals(receipt.get().session()))) return;
    var m = target.get();
    switch (event.type()) {
      case AGENT_RUN_STARTED -> {
        if (receipt.isEmpty())
          store.adoptRun(m, event.runId(), event.projectId(), event.sessionId());
        reply(event.runId() + ":running", m, "処理中です。Run: " + event.runId());
      }
      // Final text is read from the persisted completed Turn after runner cleanup.
      // Intermediate assistant messages and token deltas never become Slack posts.
      case TOOL_FAILED -> {
        if (event.payload() instanceof ToolFailedPayload payload
            && payload.error() != null
            && "PermissionRequired".equals(payload.error().errorType())) {
          store.notifyRun(
              event.runId(),
              "WAITING_APPROVAL",
              event.runId() + ":approval",
              m,
              SlackReplies.parts(
                  "承認待ちです。既存 UI／Shell の /approval で確認・承認または拒否し、既存のチェックポイント再開操作を使ってください。Slack の発言は承認として扱いません。"));
        }
      }
      case AGENT_RUN_COMPLETED, AGENT_RUN_FAILED -> {
        if (receipt.isEmpty())
          reply(
              event.runId() + ":terminal",
              m,
              event.type() == AgentEventType.AGENT_RUN_COMPLETED
                  ? "再開した Run が完了しました。"
                  : "再開した Run が失敗しました。既存 UI を確認してください。");
      }
      default -> {}
    }
  }

  /** Slackとの連携に関する返信を処理する。 */
  private void reply(String id, SlackMessage m, String text) {
    store.replyParts(id, m, SlackReplies.parts(text));
  }

  /**
   * Slackとの連携に関する送信を処理する。
   *
   * <p>One attempt per channel per tick; rate-limited attempts wait in the durable outbox.
   */
  public void send(SlackTransport transport) {
    var attempted = new HashSet<String>();
    for (var row : store.outbox(clock.millis())) {
      if (!p.getAllowedWorkspaceIds().contains(row.team())
          || !p.getAllowedChannelIds().contains(row.channel())
          || !p.getAllowedUserIds().contains(row.requester())) {
        store.suppress(row.id());
        continue;
      }
      if (!attempted.add(row.team() + ":" + row.channel()) || !store.claimSend(row.id())) continue;
      SlackTransport.Delivery result;
      try {
        result = transport.send(row);
      } catch (RuntimeException error) {
        result = SlackTransport.Delivery.unknown();
      }
      switch (result.state()) {
        case "SENT" -> store.sent(row.id(), result.ts());
        case "RETRY" -> store.retry(row.id(), clock.millis() + result.retryAfterSeconds() * 1000L);
        default -> store.sendFailed(row.id(), !result.state().equals("FAILED"));
      }
    }
  }
}
