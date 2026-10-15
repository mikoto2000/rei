package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.timing.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class TimingController {
  private final TimingRecorder recorder;
  private final ProjectRegistry projects;
  private final SessionLifecycle sessions;

  public record Text(String text) {}

  public TimingController(
      TimingRecorder recorder, ProjectRegistry projects, SessionLifecycle sessions) {
    this.recorder = recorder;
    this.projects = projects;
    this.sessions = sessions;
  }

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
