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

  private java.util.function.Function<String, dev.mikoto2000.rei.context.TaskStateSnapshot>
      progress = runId -> null;
  private java.util.function.Function<Runnable, AutoCloseable> progressChanges =
      listener -> () -> {};

  /** 表示側には記録ストアの変更操作を渡さず、読み取りと購読だけを接続する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void bindProgress(dev.mikoto2000.rei.context.TaskProgressStore store) {
    progress = store::get;
    progressChanges = store::onChange;
  }

  public AutoCloseable onProgressChange(Runnable listener) {
    return progressChanges.apply(listener);
  }

  /** 各 Run の工程を全て表示する。完了済みの過去 Run は入力欄に残さない。 */
  public List<String> progressRows() {
    var rows = new java.util.ArrayList<String>();
    for (var run : runs.activeExecutions()) {
      if (run.type() != dev.mikoto2000.rei.core.execution.ExecutionType.AGENT) continue;
      var state = progress.apply(run.id());
      String identity = projectName(run.projectId()) + " / Run " + run.id();
      if (state == null || state.completed().isEmpty() && state.pending().isEmpty()) {
        rows.add("全体進捗 [" + identity + "]: 工程を確認中");
        rows.add("  " + progressText(run.summary()));
        continue;
      }
      int completed = state.completed().size();
      String status =
          switch (state.status()) {
            case "COMPLETED" -> state.pending().isEmpty() ? "完了" : "完了通知・未完了工程あり";
            case "BLOCKED" -> "進行不能";
            default -> "作業中";
          };
      rows.add(
          "全体進捗 ["
              + identity
              + "]: "
              + completed
              + " / "
              + (completed + state.pending().size())
              + " 工程完了（"
              + status
              + "）");
      rows.add("  目的: " + progressText(state.goal()));
      state.completed().forEach(item -> rows.add("  ✓ " + progressText(item)));
      state.pending().forEach(item -> rows.add("  ○ " + progressText(item)));
    }
    return List.copyOf(rows);
  }

  private static String progressText(String text) {
    return dev.mikoto2000.rei.toolcontract.CredentialMask.redact(text)
        .replaceAll("[\\p{Cntrl}\\s]+", " ")
        .strip();
  }

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
                  + run.summary()
                  + "  [run="
                  + run.id()
                  + "]";
            })
        .toList();
  }
}
