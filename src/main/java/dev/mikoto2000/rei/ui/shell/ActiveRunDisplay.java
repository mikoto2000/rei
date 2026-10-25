package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.chat.ActiveRun;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;

/** ターミナルでの対話に必要な活動中実行表示を扱う。 */
@Component
public class ActiveRunDisplay {
  /** 実行一覧。 */
  private final ConversationInputRouter runs;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、ActiveRunDisplayを初期化する。 */
  public ActiveRunDisplay(ConversationInputRouter runs, ProjectService projects, Clock clock) {
    this.runs = runs;
    this.projects = projects;
    this.clock = clock;
  }

  /** ターミナルでの対話で用いるプロンプト状態を返す。 */
  public String promptStatus() {
    return "["
        + ActiveRun.summary(projects.currentContext().name())
        + "] ["
        + runs.activeExecutions().size()
        + " running]";
  }

  /** ターミナルでの対話で用いる要約を返す。 */
  public String summary() {
    return "Active runs: " + runs.activeExecutions().size() + " (/runs for details)";
  }

  /** ターミナルでの対話で用いるプロジェクト名前を返す。 */
  public String projectName(String id) {
    var all = projects.registeredProjects();
    var project = all.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    if (project == null) return id == null ? "unscoped" : id;
    String name = ActiveRun.summary(project.name());
    return all.stream().filter(p -> p.name().equals(project.name())).count() > 1
        ? name + " [" + id.substring(0, 8) + "]"
        : name;
  }

  /** 通常状態を維持しつつ、Agent の停止進行状況を同じ台帳から読む。 */
  private String status(dev.mikoto2000.rei.core.execution.ActiveExecution run) {
    if (run.type() != dev.mikoto2000.rei.core.execution.ExecutionType.AGENT) return run.status();
    var diagnostic = runs.cancellationDiagnostic(run.id());
    return diagnostic.status().equals("NOT_FOUND") ? run.status() : diagnostic.status();
  }

  /** ターミナルでの対話で用いる行一覧を返す。 */
  public List<String> rows() {
    return runs.activeExecutions().stream()
        .map(
            run -> {
              long seconds =
                  Math.max(0, Duration.between(run.startedAt(), clock.instant()).toSeconds());
              return projectName(run.projectId())
                  + "  "
                  + run.type()
                  + "  "
                  + status(run)
                  + "  "
                  + String.format("%02d:%02d", seconds / 60, seconds % 60)
                  + "  "
                  + run.summary() + "  [run=" + run.id() + "]";
            })
        .toList();
  }
}
