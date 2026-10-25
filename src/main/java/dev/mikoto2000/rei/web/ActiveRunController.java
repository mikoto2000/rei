package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.ui.shell.ActiveRunDisplay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 活動中実行をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ActiveRunController {
  /** 表示。 */
  private final ActiveRunDisplay display;

  /** キャンセル。 */
  private final CommandCancellationService cancellation;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡すキャンセルを表す。
   *
   * @param accepted 受付済み
   */
  public record Cancellation(
      boolean accepted, String status, java.util.List<CommandCancellationService.Diagnostic> runs) {
    /** 既存の Java 呼び出し元を維持する。 */
    public Cancellation(boolean accepted) {
      this(accepted, accepted ? "CANCEL_REQUESTED" : "NOT_FOUND", java.util.List.of());
    }
  }

  /** 指定された値や利用先を使って、ActiveRunControllerを初期化する。 */
  public ActiveRunController(
      ActiveRunDisplay display, CommandCancellationService cancellation, ProjectRegistry projects) {
    this.display = display;
    this.cancellation = cancellation;
    this.projects = projects;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping("/api/v1/runs/active")
  public Text list() {
    var rows = display.rows();
    return new Text(
        "Active Executions\n"
            + (rows.isEmpty()
                ? "No active runs."
                : "PROJECT  TYPE  STATUS  ELAPSED  REQUEST\n" + String.join("\n", rows)));
  }

  /** 対象の実行をキャンセルする。 */
  @PostMapping("/api/v1/projects/{projectId}/runs/cancel")
  public Cancellation cancel(@PathVariable String projectId) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    boolean accepted = cancellation.cancelProject(project.id());
    var diagnostics = cancellation.projectDiagnostics(project.id());
    String status =
        diagnostics.stream().anyMatch(d -> d.status().equals("CANCEL_FAILED"))
            ? "CANCEL_FAILED"
            : diagnostics.stream().anyMatch(d -> d.status().equals("CANCEL_STALLED"))
                ? "CANCEL_STALLED"
                : accepted || !diagnostics.isEmpty() ? "CANCEL_REQUESTED" : "NOT_FOUND";
    return new Cancellation(accepted, status, diagnostics);
  }
}
