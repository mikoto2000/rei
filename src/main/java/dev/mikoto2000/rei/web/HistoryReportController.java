package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.conversation.HistorySearchScope;
import dev.mikoto2000.rei.conversation.HistoryShellService;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.ui.shell.HistoryCommand;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 履歴レポートをWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/history")
public final class HistoryReportController {
  /** レポート。 */
  private final HistoryCommand report;

  /** 会話一覧。 */
  private final SessionQueryService sessions;

  /** 選択内容。 */
  private final ProjectService selection;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** 指定された値や利用先を使って、HistoryReportControllerを初期化する。 */
  public HistoryReportController(
      HistoryShellService history,
      SessionQueryService sessions,
      ProjectService selection,
      ProjectRegistry projects) {
    this.report = new HistoryCommand(history, sessions, selection);
    this.sessions = sessions;
    this.selection = selection;
    this.projects = projects;
  }

  /** Web APIによる操作で用いる選択中を返す。 */
  private Text selected(String projectId, String sessionId, Supplier<String> action) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    var project =
        projects.resolveById(projectId).orElseThrow(() -> new ResourceNotFoundException("Project"));
    if (sessionId != null && !sessions.getSession(sessionId).projectId().equals(project.id()))
      throw new IllegalArgumentException("Session belongs to another Project");
    try (var scope = selection.newClient().open()) {
      selection.cd(project.root().toString());
      selection.selectSession(sessionId);
      return new Text(action.get());
    }
  }

  /** Web APIによる操作で用いる会話一覧を返す。 */
  @GetMapping("/report")
  public Text sessions(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    return new Text(report.reportSessions(projectId, limit, cursor));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/show")
  public Text show(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String conversation,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor) {
    if (conversation == null && cursor != null)
      throw new IllegalArgumentException("--cursor requires a session ID");
    return selected(
        selectedProjectId, null, () -> report.reportSession(conversation, limit, cursor));
  }

  /** Web APIによる操作で用いる会話一覧を返す。 */
  @GetMapping("/conversations")
  public Text conversations(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String project,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return selected(
        selectedProjectId, null, () -> report.reportConversations(project, limit, offset));
  }

  /** Web APIによる操作で用いる会話を返す。 */
  @GetMapping("/conversation")
  public Text conversation(
      @RequestParam String selectedProjectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String project,
      @RequestParam(required = false) String conversation,
      @RequestParam(defaultValue = "50") int last,
      @RequestParam(defaultValue = "false") boolean all) {
    return selected(
        selectedProjectId,
        sessionId,
        () -> report.reportConversation(project, conversation, last, all));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  @GetMapping("/search")
  public Text search(
      @RequestParam String selectedProjectId,
      @RequestParam String query,
      @RequestParam(defaultValue = "CURRENT_PROJECT_PREFERRED") HistorySearchScope scope,
      @RequestParam(defaultValue = "10") int limit) {
    return selected(selectedProjectId, null, () -> report.reportSearch(query, scope, limit));
  }
}
