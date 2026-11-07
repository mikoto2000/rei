package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRunStateStore;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.working.WorkingSet;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.BackgroundExecutionPayload;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.ToIntFunction;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ターミナルでの対話に必要なプロジェクトシェル作業状況を扱う。
 *
 * <p>Shell restoration and live rendering share AgentEvent and the same renderer, with different
 * display modes.
 */
@Component
public class ProjectShellActivity implements AgentEventListener {
  /** 再開概要。 */
  private dev.mikoto2000.rei.checkpoint.ResumeOverviewService resumeOverview;

  /** 再開確認済み。 */
  private dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory resumeSeen =
      new dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory();

  /** ターミナルでの対話で使用する再開概要を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public synchronized void setResumeOverview(
      dev.mikoto2000.rei.checkpoint.ResumeOverviewService service) {
    resumeOverview = service;
  }

  /** 作業文脈。 */
  private dev.mikoto2000.rei.workcontext.WorkContextPresenter workContext;

  /** 作業文脈確認済み。 */
  private final java.util.Set<String> workContextSeen = new java.util.HashSet<>();

  /** ターミナルでの対話で使用する作業文脈を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setWorkContext(dev.mikoto2000.rei.workcontext.WorkContextPresenter presenter) {
    workContext = presenter;
  }

  /** プロジェクト一覧。 */
  private final java.util.function.Supplier<ProjectClient> currentClient;

  private final java.util.function.Supplier<ProjectContext> currentProject;
  private final java.util.function.Supplier<String> currentSessionId;

  /** 保存先。 */
  private final BiFunction<String, Integer, List<AgentEvent>> recentEvents;

  /** 状態一覧。 */
  private final ProjectRunStateStore states;

  /** 作業中集合。 */
  private final IntSupplier workingSetSize;

  /** 記憶。 */
  private final ToIntFunction<String> conversationSize;

  /** 出力。 */
  private ShellEventOutput output;

  /** 表示への変換。 */
  private ShellAgentEventRenderer renderer;

  /** 全体共通表示への変換。 */
  private ShellAgentEventRenderer globalRenderer;

  /** 表示対象プロジェクト。 */
  private String visibleProject;

  /** 表示対象会話。 */
  private String visibleSession;

  /** クライアント。 */
  private ProjectClient client;

  /** 表示への変換一覧。 */
  private final java.util.Map<String, ShellAgentEventRenderer> renderers =
      new java.util.HashMap<>();

  /** 選択可能な設定。 */
  private ShellAgentEventRenderer.TopicNotificationOptions options =
      ShellAgentEventRenderer.TopicNotificationOptions.summary();

  /** 直近上限。 */
  @Value("${rei.events.recent-limit:20}")
  private int recentLimit = 20;

  /** 活動中実行一覧。 */
  private ActiveRunDisplay activeRuns;

  /** ターミナルでの対話で使用する活動中実行一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public synchronized void setActiveRuns(ActiveRunDisplay activeRuns) {
    this.activeRuns = activeRuns;
  }

  /** 指定された値や利用先を使って、ProjectShellActivityを初期化する。 */
  public ProjectShellActivity(
      ProjectService projects,
      ProjectAgentEventStore store,
      ProjectRunStateStore states,
      WorkingSet workingSet,
      ChatMemory memory) {
    this.currentClient = () -> projects.currentClient();
    this.currentProject = () -> projects.currentContext();
    this.currentSessionId = () -> projects.currentSessionId();
    this.recentEvents = (project, limit) -> store.recent(project, limit);
    this.states = states;
    this.workingSetSize = () -> workingSet.getFiles().size();
    this.conversationSize = id -> memory.get(id).size();
  }

  /** ターミナルでの対話に関する関連付けを処理する。 */
  public synchronized void attach(ShellEventOutput output) {
    attach(output, options);
  }

  /** ターミナルでの対話に関する関連付けを処理する。 */
  public synchronized void attach(
      ShellEventOutput output, ShellAgentEventRenderer.TopicNotificationOptions options) {
    this.output = output;
    this.options = options;
    client = currentClient.get();
    visibleSession = currentSessionId.get();
    visibleProject = currentProject.get().id();
    renderers.clear();
    renderer = rendererFor(visibleProject);
    globalRenderer = new ShellAgentEventRenderer(output, options);
    resumeSeen = new dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory();
    if (resumeOverview != null && resumeOverview.enabled()) {
      try {
        String summary = resumeOverview.startupSummary();
        if (!summary.isBlank()) output.println(summary);
      } catch (RuntimeException error) {
        overviewUnavailable(error);
      }
      presentResume(currentProject.get());
    }
    presentWorkContext(currentProject.get(), visibleSession);
  }

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  public synchronized void onEvent(AgentEvent event) {
    if (renderer == null) return;
    refreshSession();
    if (event.type() == AgentEventType.APPLICATION_SHUTDOWN_STARTED) renderer.finish();
    if (event.payload() instanceof BackgroundExecutionPayload payload) {
      renderer.renderBackgroundExecution(
          payload,
          activeRuns == null ? event.projectId() : activeRuns.projectName(event.projectId()));
      return;
    }
    if (visibleProject.equals(event.projectId())
        && event.sessionId() != null
        && !visibleConversation().equals(event.sessionId())) return;
    if (activeRuns != null
        && event.projectId() != null
        && !event.projectId().equals(visibleProject)
        && (event.type() == AgentEventType.AGENT_RUN_COMPLETED
            || event.type() == AgentEventType.AGENT_RUN_FAILED)) {
      renderer.finish();
      output.println("[" + event.type().value() + "] " + activeRuns.projectName(event.projectId()));
      output.flush();
    }
    if (event.projectId() == null) globalRenderer.onEvent(event);
    else rendererFor(event.projectId()).onEvent(event);
  }

  /** 保存済みの記録から、利用する状態を復元する。 */
  public synchronized void restore(ProjectContext project) {
    if (output == null) return;
    refreshSession(false);
    renderer.finish();
    visibleProject = project.id();
    renderer = rendererFor(visibleProject);
    output.println("Switched project: " + project.name());
    presentResume(project);
    presentWorkContext(project, visibleSession);
    if (activeRuns != null) output.println(activeRuns.summary());
    String conversation =
        visibleSession == null ? project.conversationId("chat:main") : visibleSession;
    output.println(
        "Conversation restored: "
            + (visibleSession == null ? "chat:main" : visibleSession)
            + " ("
            + conversationSize.applyAsInt(conversation)
            + " messages)");
    output.println("Working Set restored: " + workingSetSize.getAsInt() + " items");
    if (visibleSession == null)
      output.println(
          "Last run: "
              + states.read(project.id()).map(ProjectRunStateStore.State::status).orElse("none"));
    output.println("Recent activity:");
    for (var event : recentEvents.apply(project.id(), Math.max(1, Math.min(recentLimit, 1000))))
      if (event.sessionId() == null || visibleConversation().equals(event.sessionId()))
        renderer.onRecentEvent(event);
    output.flush();
  }

  /**
   * ターミナルでの対話に関する再確認会話を処理する。
   *
   * <p>Refresh on command completion and before asynchronous events; never replay a previous run as
   * current.
   */
  public synchronized void refreshSession() {
    refreshSession(true);
  }

  /** ターミナルでの対話で用いる表示対象会話を返す。 */
  private String visibleConversation() {
    // No selected Session means the legacy Shell conversation, not every client Session.
    return visibleSession == null ? "project:" + visibleProject + ":chat:main" : visibleSession;
  }

  /** ターミナルでの対話に関する再確認会話を処理する。 */
  private void refreshSession(boolean showContext) {
    if (client == null || renderer == null) return;
    String selected;
    ProjectContext selectedProject;
    try (var scope = client.open()) {
      selected = currentSessionId.get();
      selectedProject = currentProject.get();
    }
    if (java.util.Objects.equals(selected, visibleSession)) return;
    renderer.finish();
    visibleSession = selected;
    if (showContext) presentWorkContext(selectedProject, selected);
    renderers.clear();
    renderer = rendererFor(visibleProject);
  }

  /** ターミナルでの対話に関する存在する作業文脈を処理する。 */
  private void presentWorkContext(ProjectContext project, String session) {
    if (workContext == null || output == null) return;
    try {
      workContext.presentSaved(project, session, workContextSeen).ifPresent(output::println);
      output.flush();
    } catch (RuntimeException error) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Work Context presentation unavailable ({})", error.getClass().getSimpleName());
    }
  }

  /** ターミナルでの対話に関する存在する再開を処理する。 */
  private void presentResume(ProjectContext project) {
    if (resumeOverview == null || !resumeOverview.enabled()) return;
    try {
      String text =
          resumeSeen.present(
              resumeOverview.overview(project.id()),
              resumeOverview.zone(java.time.ZoneId.systemDefault()),
              false);
      if (!text.isBlank()) output.println(text);
      output.flush();
    } catch (RuntimeException error) {
      overviewUnavailable(error);
    }
  }

  /** ターミナルでの対話に関する概要利用不可を処理する。 */
  private void overviewUnavailable(RuntimeException error) {
    dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("Resume overview unavailable ({})", error.getClass().getSimpleName());
  }

  /** ターミナルでの対話で用いる表示への変換対象を返す。 */
  private synchronized ShellAgentEventRenderer rendererFor(String projectId) {
    var rendererOptions = options;
    return renderers.computeIfAbsent(
        projectId,
        id ->
            new ShellAgentEventRenderer(
                new ShellEventOutput() {
                  /** 利用者に結果を伝えるため、を出力する。 */
                  public void print(String text) {
                    if (id.equals(visibleProject)) output.print(text);
                  }

                  /** ターミナルでの対話に関する改行付きの出力を処理する。 */
                  public void println(String text) {
                    if (id.equals(visibleProject)) output.println(text);
                  }

                  /** ターミナルでの対話に関する保存の確定を処理する。 */
                  public void flush() {
                    if (id.equals(visibleProject)) output.flush();
                  }

                  /** ターミナルでの対話に関する承認操作一覧を処理する。 */
                  public void approvalActions(String project, String session, String approval) {
                    if (id.equals(visibleProject))
                      output.approvalActions(project, session, approval);
                  }

                  /** ターミナルでの対話に関する承認必須を処理する。 */
                  public void approvalRequired(String message) {
                    if (id.equals(visibleProject)) output.approvalRequired(message);
                  }
                },
                rendererOptions));
  }
}
