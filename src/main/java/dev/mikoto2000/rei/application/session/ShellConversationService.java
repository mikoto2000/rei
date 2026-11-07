package dev.mikoto2000.rei.application.session;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ResponseStyle;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * シェル会話のユースケースを実現する。
 *
 * <p>Selection belongs to a Shell client, never to the process or an execution thread.
 */
public final class ShellConversationService {
  /** 受付窓口。 */
  private final dev.mikoto2000.rei.application.input.ConversationInputGateway gateway;

  /** 取得記録一覧。 */
  @FunctionalInterface
  private interface AcceptCapture {
    void record(Object client, String conversation, String submission, String run);
  }

  private record CaptureHooks(
      AcceptCapture accept, java.util.function.Consumer<String> startFailed) {}

  private CaptureHooks captureHooks;

  /** 会話の継続管理で使用する取得記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCaptures(dev.mikoto2000.rei.llm.capture.CaptureStore captures) {
    captureHooks =
        captures == null ? null : new CaptureHooks(captures::accept, captures::startFailed);
  }

  /**
   * 会話の継続管理で受け渡す自分が所有する対象を表す。
   *
   * @param client クライアント
   * @param context 文脈
   */
  private record Owned(
      dev.mikoto2000.rei.core.project.ProjectClient client, AgentRunContext context) {}

  /** 自分が所有する対象。 */
  private final java.util.concurrent.ConcurrentMap<String, Owned> owned =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 会話の継続管理に関するremember所有者を処理する。 */
  private void rememberOwner(
      dev.mikoto2000.rei.core.project.ProjectClient client, AgentRunContext context) {
    if (containsRun != null)
      owned
          .entrySet()
          .removeIf(
              entry -> !containsRun.test(entry.getValue().context().projectId(), entry.getKey()));
    owned.put(context.runId(), new Owned(client, context));
  }

  /** 会話の継続管理において、owns実行に該当するか判定する。 */
  public boolean ownsRun(
      dev.mikoto2000.rei.core.project.ProjectClient client, AgentRunContext context) {
    var owner = owned.get(context.runId());
    return owner != null && owner.client() == client && owner.context().equals(context);
  }

  /** 活動中キャンセル。 */
  private java.util.function.Predicate<AgentRunContext> activeCancellation = context -> false;

  /** 通知に応じて状態や表示を更新するため、活動中キャンセルを受け付ける。 */
  public void onActiveCancellation(java.util.function.Predicate<AgentRunContext> cancellation) {
    activeCancellation = java.util.Objects.requireNonNull(cancellation);
  }

  /**
   * 利用者が不要とした実行を止めるため、選択中活動中をキャンセルする。
   *
   * <p>Voice stop is confined to this captured client and selected Session, never the whole
   * project.
   */
  public int cancelSelectedActive(
      dev.mikoto2000.rei.core.project.ProjectClient client,
      dev.mikoto2000.rei.application.input.ConversationInput input) {
    if (input.source() != dev.mikoto2000.rei.application.input.InputSource.VOICE)
      throw new IllegalArgumentException("VOICE control required");
    var target = input.target();
    synchronized (client) {
      if (!isSelected(client, target)) throw new IllegalStateException("Voice target changed");
      if (!gateway.isNovel(input) || containsRun == null) return 0;
      var active = activeRunIds.get();
      int cancelled = 0;
      for (var owner : owned.values()) {
        var context = owner.context();
        if (owner.client() == client
            && active.contains(context.runId())
            && java.util.Objects.equals(context.projectId(), target.project().id())
            && java.util.Objects.equals(context.conversationId(), target.sessionId())
            && context.projectRoot().equals(target.project().root().toAbsolutePath().normalize())
            && activeCancellation.test(context)) cancelled++;
      }
      return cancelled;
    }
  }

  /** 状態表示Delegations。 */
  private org.springframework.beans.factory.ObjectProvider<
          dev.mikoto2000.rei.dot.DotDelegationService>
      dotDelegations;

  /** 会話の継続管理で使用する状態表示Delegationsを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setDotDelegations(
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.dot.DotDelegationService>
          dots) {
    this.dotDelegations = dots;
  }

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final java.time.Clock clock;

  /** プロジェクト一覧。 */
  private final java.util.function.Supplier<dev.mikoto2000.rei.core.project.ProjectClient>
      currentClient;

  private final java.util.function.Supplier<dev.mikoto2000.rei.core.project.ProjectContext>
      currentProject;
  private final java.util.function.Supplier<String> selectedSessionId;
  private final java.util.function.Consumer<String> selectSession;
  private final java.util.function.Function<
          dev.mikoto2000.rei.core.project.ProjectClient, ProjectService.NotificationSelection>
      selectionOf;

  /** 受付から終了までの管理。 */
  private final Function<String, SessionMetadata> createSession;

  private final BiFunction<String, String, SessionMetadata> validateSession;
  private final BiFunction<ResponseStyle, Boolean, SessionMetadata> changeResponseStyle;
  private final BiFunction<String, String, SessionMetadata> endSession;

  /** 並行有効状態。 */
  private final boolean concurrentEnabled;

  /** 入力一覧。 */
  private final java.util.function.BiPredicate<String, String> containsRun;

  private final java.util.function.Supplier<java.util.Set<String>> activeRunIds;
  private final java.util.function.Predicate<Intervention> offerIntervention;

  private record Intervention(String project, String session, String run, String message) {}

  /** 会話の継続管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ShellConversationService(
      ProjectService projects,
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, lifecycle, dispatch, false, null);
  }

  /** 会話の継続管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ShellConversationService(
      ProjectService projects,
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch,
      boolean concurrentEnabled,
      dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs) {
    this(projects, lifecycle, dispatch, concurrentEnabled, inputs, java.time.Clock.systemUTC());
  }

  /** 指定された値や利用先を使って、ShellConversationServiceを初期化する。 */
  public ShellConversationService(
      ProjectService projects,
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch,
      boolean concurrentEnabled,
      dev.mikoto2000.rei.core.chat.ConversationInputRouter inputs,
      java.time.Clock clock) {
    this.clock = java.util.Objects.requireNonNull(clock);
    this.currentClient = () -> projects.currentClient();
    this.currentProject = () -> projects.currentContext();
    this.selectedSessionId = () -> projects.currentSessionId();
    this.selectSession = session -> projects.selectSession(session);
    this.selectionOf = client -> projects.selectionOf(client);
    this.createSession = title -> lifecycle.create(currentProject.get(), title);
    this.validateSession = (session, project) -> lifecycle.validate(session, project);
    this.changeResponseStyle =
        (style, voiceOnly) ->
            lifecycle.responseStyle(currentProject.get(), currentSessionId(), style, voiceOnly);
    this.endSession = (session, project) -> lifecycle.end(session, project);
    this.concurrentEnabled = concurrentEnabled;
    this.containsRun = inputs == null ? null : (project, run) -> inputs.containsRun(project, run);
    this.activeRunIds =
        () ->
            inputs.activeRuns().stream()
                .map(dev.mikoto2000.rei.core.chat.ActiveRun::runId)
                .collect(java.util.stream.Collectors.toSet());
    this.offerIntervention =
        request ->
            inputs.offerIntervention(
                request.project(), request.session(), request.run(), request.message());
    this.gateway =
        new dev.mikoto2000.rei.application.input.ConversationInputGateway(
            lifecycle, dispatch, inputs, clock);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(String message) {
    return submit(message, AgentRunContext.Mode.EXCLUSIVE);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(String message, AgentRunContext.Mode mode) {
    if (mode == null) mode = AgentRunContext.Mode.EXCLUSIVE;
    if (mode != AgentRunContext.Mode.EXCLUSIVE && !concurrentEnabled)
      throw new IllegalArgumentException("Concurrent conversations are disabled");
    synchronized (currentClient.get()) {
      var project = currentProject.get();
      var input =
          new dev.mikoto2000.rei.application.input.ConversationInput(
              java.util.UUID.randomUUID(),
              dev.mikoto2000.rei.application.input.InputSource.KEYBOARD,
              new dev.mikoto2000.rei.application.input.ConversationTarget(
                  project, currentSessionId()),
              message,
              clock.instant());
      var capture = captureHooks;
      var client = currentClient.get();
      var context =
          gateway.submit(
              input,
              mode,
              run -> {
                rememberOwner(client, run);
                if (dotDelegations != null)
                  dotDelegations.ifAvailable(dot -> dot.authoriseHumanInput(run, message));
                if (capture != null)
                  capture
                      .accept()
                      .record(
                          client, run.conversationId(), input.inputId().toString(), run.runId());
              },
              run -> {
                if (capture != null) capture.startFailed().accept(run.runId());
              });
      selectSession.accept(context.conversationId());
      return context;
    }
  }

  /**
   * 後から状況を確認できるよう、対象を記録する。
   *
   * <p>Captured on the interactive thread, then safe to pass to an ASR worker.
   */
  public dev.mikoto2000.rei.application.input.ConversationTarget captureTarget() {
    synchronized (currentClient.get()) {
      if (currentSessionId() == null) newConversation();
      return new dev.mikoto2000.rei.application.input.ConversationTarget(
          currentProject.get(), currentSessionId());
    }
  }

  /** 後から状況を確認できるよう、クライアントを記録する。 */
  public dev.mikoto2000.rei.core.project.ProjectClient captureClient() {
    return currentClient.get();
  }

  /** 会話の継続管理において、選択中かどうかを判定する。 */
  public boolean isSelected(
      dev.mikoto2000.rei.core.project.ProjectClient client,
      dev.mikoto2000.rei.application.input.ConversationTarget target) {
    try {
      var selection = selectionOf.apply(client);
      return selection.project().id().equals(target.project().id())
          && selection.project().root().equals(target.project().root())
          && java.util.Objects.equals(selection.conversationId(), target.sessionId());
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * 依頼を実行経路へ渡すため、選択中音声を受け付ける。
   *
   * <p>Check selection and admit the voice envelope under the same client lock as Session changes.
   */
  public AgentRunContext submitSelectedVoice(
      dev.mikoto2000.rei.core.project.ProjectClient client,
      dev.mikoto2000.rei.application.input.ConversationInput input) {
    return submitSelectedVoice(client, input, () -> {});
  }

  /** 依頼を実行経路へ渡すため、選択中音声を受け付ける。 */
  public AgentRunContext submitSelectedVoice(
      dev.mikoto2000.rei.core.project.ProjectClient client,
      dev.mikoto2000.rei.application.input.ConversationInput input,
      Runnable afterAccepted) {
    if (client == null || input.source() != dev.mikoto2000.rei.application.input.InputSource.VOICE)
      throw new IllegalArgumentException("Captured VOICE client required");
    synchronized (client) {
      if (!isSelected(client, input.target()))
        throw new IllegalStateException(
            "Voice target changed; restart voice for the selected Session");
      return gateway.submit(
          input,
          AgentRunContext.Mode.EXCLUSIVE,
          run -> rememberOwner(client, run),
          run -> owned.remove(run.runId()),
          afterAccepted);
    }
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(dev.mikoto2000.rei.application.input.ConversationInput input) {
    return gateway.submit(input, AgentRunContext.Mode.EXCLUSIVE);
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public java.util.List<dev.mikoto2000.rei.application.input.ConversationInput> pending(
      dev.mikoto2000.rei.application.input.ConversationTarget target) {
    return gateway.pending(target);
  }

  /** 利用者が不要とした実行を止めるため、待機中をキャンセルする。 */
  public boolean cancelPending(
      dev.mikoto2000.rei.application.input.ConversationTarget target, java.util.UUID inputId) {
    return gateway.cancelPending(target, inputId);
  }

  /** 通知に応じて状態や表示を更新するため、待機中キャンセルを受け付ける。 */
  public void onPendingCancellation(java.util.function.Predicate<AgentRunContext> cancellation) {
    gateway.onCancel(cancellation);
  }

  /** 会話の継続管理において、interveneに該当するか判定する。 */
  public boolean intervene(String runId, String message) {
    if (!concurrentEnabled || containsRun == null)
      throw new IllegalArgumentException("Concurrent conversations are disabled");
    synchronized (currentClient.get()) {
      return offerIntervention.test(
          new Intervention(currentProject.get().id(), currentSessionId(), runId, message));
    }
  }

  /** 新たな対象を利用できるよう、会話を作成する。 */
  public void newConversation() {
    newConversation(null);
  }

  /** 新たな対象を利用できるよう、会話を作成する。 */
  public SessionMetadata newConversation(String title) {
    synchronized (currentClient.get()) {
      var session = createSession.apply(title);
      selectSession.accept(session.sessionId());
      return session;
    }
  }

  /** 保存された状況を引き継いで、中断した作業を再開する。 */
  public void resume(String sessionId) {
    synchronized (currentClient.get()) {
      validateSession.apply(sessionId, currentProject.get().id());
      selectSession.accept(sessionId);
    }
  }

  /** 会話の継続管理で用いる現在付随情報を返す。 */
  public SessionMetadata currentMetadata() {
    synchronized (currentClient.get()) {
      var id = currentSessionId();
      return id == null ? null : validateSession.apply(id, currentProject.get().id());
    }
  }

  /** 会話の継続管理で用いる会話の応答形式を返す。 */
  public SessionMetadata responseStyle(
      dev.mikoto2000.rei.core.chat.ResponseStyle style, boolean voiceOnly) {
    java.util.Objects.requireNonNull(style);
    synchronized (currentClient.get()) {
      if (currentSessionId() == null) newConversation();
      return changeResponseStyle.apply(style, voiceOnly);
    }
  }

  /** 会話の継続管理で用いる現在会話の識別子を返す。 */
  public String currentSessionId() {
    return selectedSessionId.get();
  }

  /** 会話の継続管理で用いる終了を返す。 */
  public SessionMetadata end() {
    synchronized (currentClient.get()) {
      String id = currentSessionId();
      if (id == null) return null;
      var session = endSession.apply(id, currentProject.get().id());
      selectSession.accept(null);
      return session;
    }
  }
}
