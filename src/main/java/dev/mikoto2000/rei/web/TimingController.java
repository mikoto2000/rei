package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.timing.TimingCommand;
import dev.mikoto2000.rei.timing.TimingRecorder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 時間計測をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class TimingController {
  /** 実行時間を記録する窓口。 */
  private final TimingRecorder recorder;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 会話一覧。 */
  private final SessionLifecycle sessions;

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /** 指定された値や利用先を使って、TimingControllerを初期化する。 */
  public TimingController(
      TimingRecorder recorder, ProjectRegistry projects, SessionLifecycle sessions) {
    this.recorder = recorder;
    this.projects = projects;
    this.sessions = sessions;
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  @GetMapping("/api/v1/timing")
  public Text show(
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false) String sessionId,
      @RequestParam(defaultValue = "last") String runId,
      @RequestParam(defaultValue = "false") boolean details) {
    if (!recorder.enabled()) return new Text("時間計測は無効です");
    if (runId == null || !runId.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new IllegalArgumentException("Invalid Run ID");
    if (sessionId == null) return new Text("現在のSessionの計測履歴はありません");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (projectId == null || projectId.isBlank() || projectId.length() > 128)
      throw new IllegalArgumentException("Project ID required");
    var project =
        projects
            .resolveById(projectId)
            .orElseThrow(
                () -> new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Project"));
    sessions.validate(sessionId, project.id());
    var run =
        runId.equals("last")
            ? recorder.latest(project.id(), sessionId)
            : recorder.snapshot(project.id(), sessionId, runId);
    return new Text(
        run.map(value -> TimingCommand.report(value, details, recorder.statistics()))
            .orElse("計測履歴なし: 未計測・別Session・期限切れ・上限による破棄のいずれかです"));
  }
}
