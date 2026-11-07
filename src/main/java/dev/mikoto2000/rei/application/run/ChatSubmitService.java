package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** チャット受付のユースケースを実現する。 */
public final class ChatSubmitService {
  /** プロジェクト一覧。 */
  private final Function<String, Optional<ProjectContext>> findProject;

  /** 会話一覧。 */

  /** 実行一覧。 */
  private final java.util.function.Consumer<AgentRunContext> registerRun;

  private final java.util.function.Consumer<String> forgetRun;

  /** 受付から終了までの管理。 */
  private final Function<
          AgentRunContext.Mode,
          java.util.function.BiFunction<ProjectContext, WebAdmission, AgentRunContext>>
      webAdmission;

  private final Function<
          dev.mikoto2000.rei.mcp.McpAdmission,
          java.util.function.BiFunction<ProjectContext, McpMessage, AgentRunContext>>
      mcpAdmission;

  private record WebAdmission(
      String session, String message, String key, String fingerprint, String client) {}

  private record McpMessage(String session, String message) {}

  /** 実行先への引き渡し。 */

  /** 並行有効状態。 */
  private final boolean concurrentEnabled;

  /** 状態表示Delegations。 */
  private org.springframework.beans.factory.ObjectProvider<
          dev.mikoto2000.rei.dot.DotDelegationService>
      dotDelegations;

  /** 実行の受付と状態管理で使用する状態表示Delegationsを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setDotDelegations(
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.dot.DotDelegationService>
          dots) {
    this.dotDelegations = dots;
  }

  /** 取得記録一覧。 */
  @FunctionalInterface
  private interface AcceptCapture {
    void record(String client, String conversation, String submission, String run);
  }

  private record CaptureHooks(
      AcceptCapture accept, java.util.function.Consumer<String> startFailed) {}

  private CaptureHooks captureHooks;

  /** 実行の受付と状態管理で使用する取得記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCaptures(dev.mikoto2000.rei.llm.capture.CaptureStore store) {
    captureHooks = store == null ? null : new CaptureHooks(store::accept, store::startFailed);
  }

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatSubmitService(
      ProjectRegistry projects,
      SessionRegistry sessions,
      RunRegistry runs,
      SessionRepository repository,
      Clock clock,
      BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, sessions, runs, new SessionLifecycle(repository, clock), dispatch);
  }

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatSubmitService(
      ProjectRegistry projects,
      SessionRegistry sessions,
      RunRegistry runs,
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch) {
    this(projects, sessions, runs, lifecycle, dispatch, false);
  }

  /** 指定された値や利用先を使って、ChatSubmitServiceを初期化する。 */
  public ChatSubmitService(
      ProjectRegistry projects,
      SessionRegistry sessions,
      RunRegistry runs,
      SessionLifecycle lifecycle,
      BiConsumer<AgentRunContext, String> dispatch,
      boolean concurrentEnabled) {
    this.findProject = id -> projects.resolveById(id);
    this.registerRun = context -> runs.register(context);
    this.forgetRun = run -> runs.forget(run);
    this.webAdmission =
        mode ->
            (project, request) ->
                lifecycle.submit(
                    project,
                    request.session(),
                    request.message(),
                    AgentRunContext.RequestSource.WEB,
                    mode,
                    false,
                    request.key(),
                    request.fingerprint(),
                    context -> {
                      var capture = captureHooks;
                      sessions.remember(context.conversationId(), project.id());
                      try {
                        registerRun.accept(context);
                        if (dotDelegations != null)
                          dotDelegations.ifAvailable(
                              dot -> dot.authoriseHumanInput(context, request.message()));
                        if (capture != null && request.client() != null)
                          capture
                              .accept()
                              .record(
                                  "cli:" + request.client(),
                                  context.conversationId(),
                                  request.key() == null ? context.runId() : request.key(),
                                  context.runId());
                        dispatch.accept(context, request.message());
                      } catch (RuntimeException | Error error) {
                        if (capture != null) capture.startFailed().accept(context.runId());
                        forgetRun.accept(context.runId());
                        sessions.forget(context.conversationId());
                        throw error;
                      }
                    });
    this.mcpAdmission =
        admission ->
            (project, request) ->
                lifecycle.submit(
                    project,
                    request.session(),
                    request.message(),
                    AgentRunContext.RequestSource.MCP,
                    AgentRunContext.Mode.READ_ONLY,
                    false,
                    null,
                    null,
                    admission,
                    context -> {
                      sessions.remember(context.conversationId(), project.id());
                      try {
                        registerRun.accept(context);
                        dispatch.accept(context, request.message());
                      } catch (RuntimeException | Error error) {
                        forgetRun.accept(context.runId());
                        sessions.forget(context.conversationId());
                        throw error;
                      }
                    });
    this.concurrentEnabled = concurrentEnabled;
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(String message, String projectId, String sessionId) {
    return submit(message, projectId, sessionId, AgentRunContext.Mode.EXCLUSIVE);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(
      String message, String projectId, String sessionId, AgentRunContext.Mode mode) {
    return submit(message, projectId, sessionId, mode, null);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(
      String message, String projectId, String sessionId, AgentRunContext.Mode mode, String key) {
    return submit(message, projectId, sessionId, mode, key, null);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public AgentRunContext submit(
      String message,
      String projectId,
      String sessionId,
      AgentRunContext.Mode mode,
      String key,
      String clientId) {
    if (clientId != null
        && !clientId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid CLI identity");
    if (mode == null) mode = AgentRunContext.Mode.EXCLUSIVE;
    if (mode != AgentRunContext.Mode.EXCLUSIVE && !concurrentEnabled)
      throw new IllegalArgumentException("Concurrent conversations are disabled");
    if (message == null || message.isBlank() || projectId == null || projectId.isBlank())
      throw new IllegalArgumentException("message and projectId are required");
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    String fingerprint = null;
    if (key != null)
      try {
        fingerprint =
            dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
                dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(
                    java.util.Arrays.asList(projectId, sessionId, message, mode.name())));
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot fingerprint request", error);
      }
    return webAdmission
        .apply(mode)
        .apply(project, new WebAdmission(sessionId, message, key, fingerprint, clientId));
  }

  /**
   * 依頼を実行経路へ渡すため、MCPを受け付ける。
   *
   * <p>Authenticated adapter context; fixed READ_ONLY and no shell selection side effects.
   */
  public AgentRunContext submitMcp(
      String message,
      String projectId,
      String sessionId,
      dev.mikoto2000.rei.mcp.McpAdmission admission) {
    if (!concurrentEnabled)
      throw new IllegalArgumentException("Concurrent conversations are disabled");
    var project =
        findProject.apply(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    return mcpAdmission.apply(admission).apply(project, new McpMessage(sessionId, message));
  }
}
