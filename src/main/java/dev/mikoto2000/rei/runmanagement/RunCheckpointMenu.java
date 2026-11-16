package dev.mikoto2000.rei.runmanagement;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Numbered checkpoint management used by the local and connected shells. */
public final class RunCheckpointMenu {
  private RunCheckpointMenu() {}

  public record Run(
      String projectId,
      String projectName,
      String runId,
      String request,
      String status,
      String startedAt,
      String failure) {}

  public record Checkpoint(
      String taskId,
      String runId,
      String originalRunId,
      String savedAt,
      String status,
      long revision,
      String request,
      boolean discardable) {}

  public interface Access {
    List<Run> runs() throws IOException, InterruptedException;

    List<Checkpoint> checkpoints(Run run) throws IOException, InterruptedException;

    void discard(Run run, Checkpoint checkpoint) throws IOException, InterruptedException;
  }

  public static void open(Access access, Function<String, String> input, Consumer<String> out)
      throws IOException, InterruptedException {
    var runs = access.runs();
    while (true) {
      out.accept("実行中・失敗・停止済みの Run");
      for (int i = 0; i < runs.size(); i++) {
        var run = runs.get(i);
        out.accept(
            "%d. %s / %s / %s / %s / %s%s"
                .formatted(
                    i + 1,
                    run.projectName(),
                    run.runId(),
                    run.status(),
                    run.startedAt() == null ? "開始時刻不明" : run.startedAt(),
                    run.request(),
                    run.failure().isBlank() ? "" : " / " + run.failure()));
      }
      int selection = choose(input.apply("Run 番号 [0: 戻る、q: 中止] "), runs.size(), out);
      if (selection == -1 || selection == 0) return;
      if (selection == -2) continue;
      var run = runs.get(selection - 1);
      var checkpoints = access.checkpoints(run);
      while (true) {
        if (checkpoints.isEmpty()) out.accept("チェックポイントがありません。");
        for (int i = 0; i < checkpoints.size(); i++) {
          var checkpoint = checkpoints.get(i);
          out.accept(
              "%d. %s / Run %s / %s / rev %d / %s / %s%s"
                  .formatted(
                      i + 1,
                      checkpoint.taskId(),
                      checkpoint.runId(),
                      checkpoint.savedAt(),
                      checkpoint.revision(),
                      checkpoint.status(),
                      checkpoint.request(),
                      checkpoint.discardable() ? "" : " / 破棄不可"));
        }
        int selected = choose(input.apply("チェックポイント番号 [0: 戻る、q: 中止] "), checkpoints.size(), out);
        if (selected == -1) return;
        if (selected == 0) break;
        if (selected == -2) continue;
        var checkpoint = checkpoints.get(selected - 1);
        if (!checkpoint.discardable()) continue;
        out.accept("対象タスク: " + checkpoint.taskId() + " / " + checkpoint.request());
        out.accept("このタスクをチェックポイントから再開できなくなります");
        int confirm = choose(input.apply("チェックポイントを破棄 [1: 破棄、0: 戻る、q: 中止] "), 1, out);
        if (confirm == -1) return;
        if (confirm != 1) continue;
        try {
          access.discard(run, checkpoint);
          out.accept("チェックポイントを破棄しました。");
        } catch (IOException | RuntimeException failure) {
          out.accept("破棄できませんでした。最新状態を再取得します。");
        }
        runs = access.runs();
        checkpoints = access.checkpoints(run);
      }
    }
  }

  private static int choose(String answer, int max, Consumer<String> out) {
    if (answer == null || answer.strip().equalsIgnoreCase("q")) return -1;
    String value = answer.strip();
    if (value.matches("0|[1-9][0-9]{0,8}")) {
      int number = Integer.parseInt(value);
      if (number <= max) return number;
    }
    out.accept("表示された番号を入力してください。");
    return -2;
  }
}
