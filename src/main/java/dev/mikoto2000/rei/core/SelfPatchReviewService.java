package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.process.BuildTestFailureDiagnosis;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner;
import dev.mikoto2000.rei.externalagent.ExternalAgentResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Self変更差分レビューのユースケースを実現する。
 *
 * <p>Explicit bounded test -> static patch review -> final test cycle. Never claims semantic
 * correctness.
 */
public final class SelfPatchReviewService {
  /**
   * 共通の実行基盤で受け渡す要求を表す。
   *
   * @param testCommand 検証コマンド
   * @param timeoutSeconds タイムアウトの秒数
   */
  public record Request(String testCommand, Integer timeoutSeconds) {}

  /**
   * 共通の実行基盤で受け渡す指摘を表す。
   *
   * @param path パス
   * @param line 行
   * @param kind 種類
   */
  public record Finding(String path, long line, String kind) {}

  /**
   * 共通の実行基盤で受け渡す状態の記録を表す。
   *
   * @param version 形式のバージョン
   * @param changedFiles 変更済みファイル一覧
   * @param untracked 未登録
   * @param complete 完了
   * @param warnings 注意事項
   */
  public record Snapshot(
      String version,
      List<String> changedFiles,
      List<String> untracked,
      boolean complete,
      List<String> warnings) {
    /** 受け取った情報からSnapshotを作成する。 */
    public Snapshot {
      changedFiles = List.copyOf(changedFiles);
      untracked = List.copyOf(untracked);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * 共通の実行基盤で受け渡すレビューを表す。
   *
   * @param complete 完了
   * @param findings 指摘一覧
   * @param warnings 注意事項
   */
  public record Review(boolean complete, List<Finding> findings, List<String> warnings) {
    /** 受け取った情報からReviewを作成する。 */
    public Review {
      findings = List.copyOf(findings);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * 共通の実行基盤で受け渡す検証観測内容を表す。
   *
   * @param status 状態
   * @param exitCode 終了コード
   * @param timedOut timed出力先
   * @param logsTruncated ログ一覧省略の有無
   * @param diagnosis 診断
   */
  public record TestObservation(
      String status,
      Integer exitCode,
      boolean timedOut,
      boolean logsTruncated,
      BuildTestFailureDiagnosis diagnosis) {}

  /**
   * 共通の実行基盤で受け渡す結果を表す。
   *
   * @param root 作業ルート
   * @param status 状態
   * @param patchVersion 変更差分形式のバージョン
   * @param currentVersion 現在形式のバージョン
   * @param changedFiles 変更済みファイル一覧
   * @param initialTest 初期検証
   * @param review レビュー
   * @param finalTest 最終検証
   * @param nextActions 次操作一覧
   * @param warnings 注意事項
   */
  public record Result(
      String root,
      String status,
      String patchVersion,
      String currentVersion,
      List<String> changedFiles,
      TestObservation initialTest,
      Review review,
      TestObservation finalTest,
      List<String> nextActions,
      List<String> warnings) {}

  /** 共通の実行基盤に必要な取得記録を扱う。 */
  @FunctionalInterface
  public interface Capture {
    /** 後から状況を確認できるよう、取得記録を記録する。 */
    Snapshot capture(Path root, long deadline) throws IOException;
  }

  /** 共通の実行基盤に必要なReviewerを扱う。 */
  @FunctionalInterface
  public interface Reviewer {
    /** 共通の実行基盤で用いるレビューを返す。 */
    Review review(Path root, Snapshot snapshot, long deadline) throws IOException;
  }

  /** 共通の実行基盤に必要なTesterを扱う。 */
  @FunctionalInterface
  public interface Tester {
    /** 共通の実行基盤で用いる検証を返す。 */
    TestObservation test(Path root, String command, Duration timeout) throws IOException;
  }

  /** 取得記録。 */
  private final Capture capture;

  /** 変更を評価する窓口。 */
  private final Reviewer reviewer;

  /** 修正結果を検証する窓口。 */
  private final Tester tester;

  /** 指定された値や利用先を使って、SelfPatchReviewServiceを初期化する。 */
  public SelfPatchReviewService(Capture capture, Reviewer reviewer, Tester tester) {
    this.capture = capture;
    this.reviewer = reviewer;
    this.tester = tester;
  }

  /** 共通の実行基盤を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SelfPatchReviewService(SystemShellService shell) {
    this(shell, () -> false);
  }

  /** 指定された値や利用先を使って、SelfPatchReviewServiceを初期化する。 */
  public SelfPatchReviewService(
      SystemShellService shell, java.util.function.BooleanSupplier cancelled) {
    var processes = new ExternalAgentProcessRunner();
    var inspector = new GitPatchInspector(processes, cancelled);
    capture = inspector::capture;
    reviewer = inspector::review;
    tester =
        (root, command, timeout) -> {
          var output =
              processes.run(
                  shell.shellCommandLine(
                      shell.resolveShell(System.getenv(), System.getProperty("os.name")), command),
                  root,
                  "",
                  timeout,
                  timeout,
                  65536,
                  () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
          if (output.status() == ExternalAgentResult.Status.CANCELLED)
            throw new java.util.concurrent.CancellationException("self-review cancelled");
          boolean timedOut =
              Set.of(
                      ExternalAgentResult.Status.TOTAL_TIMEOUT,
                      ExternalAgentResult.Status.INACTIVITY_TIMEOUT)
                  .contains(output.status());
          String status =
              output.status() == ExternalAgentResult.Status.SUCCESS ? "completed" : "failed";
          return new TestObservation(
              status,
              output.exitCode(),
              timedOut,
              output.truncated(),
              BuildTestFailureDiagnosis.command(
                  status, output.exitCode(), timedOut, output.stdout(), output.stderr(), null));
        };
  }

  /** 要求された条件を満たすことを確認する。 */
  public Result verify(Path directory, Request request) throws IOException {
    return verify(directory, request, System.nanoTime() + Duration.ofSeconds(180).toNanos());
  }

  /** 要求された条件を満たすことを確認する。 */
  public Result verify(Path directory, Request request, long deadline) throws IOException {
    RunCancellation.propagate(null);
    if (request == null
        || request.testCommand() == null
        || request.testCommand().isBlank()
        || request.testCommand().length() > 4096)
      throw new IllegalArgumentException(
          "An explicit test command of 1 to 4096 characters is required");
    int seconds = request.timeoutSeconds() == null ? 30 : request.timeoutSeconds();
    if (seconds < 1 || seconds > 60)
      throw new IllegalArgumentException("Each test timeout must be 1 to 60 seconds");
    Path root = directory.toRealPath();
    remaining(deadline, 1);
    Snapshot first = null, current = null;
    TestObservation initial = null, last = null;
    Review review = null;
    var warnings = new LinkedHashSet<String>();
    warnings.add(
        "Static patch checks and explicit command exit only; semantic review and test selection remain the caller's responsibility");
    try {
      first = current = capture.capture(root, deadline);
      warnings.addAll(current.warnings());
      if (!current.complete())
        return result(root, "BLOCKED", first, current, initial, review, last, warnings);
      if (current.changedFiles().isEmpty())
        return result(root, "NO_PATCH", first, current, initial, review, last, warnings);
      initial = tester.test(root, request.testCommand(), remaining(deadline, seconds));
      RunCancellation.propagate(null);
      if (!passed(initial))
        return result(root, "INITIAL_TEST_FAILED", first, current, initial, review, last, warnings);
      current = capture.capture(root, deadline);
      warnings.addAll(current.warnings());
      if (!same(first, current))
        return result(root, "PATCH_CHANGED", first, current, initial, review, last, warnings);
      review = reviewer.review(root, current, deadline);
      RunCancellation.propagate(null);
      warnings.addAll(review.warnings());
      if (!review.complete())
        return result(root, "REVIEW_INCOMPLETE", first, current, initial, review, last, warnings);
      if (!review.findings().isEmpty())
        return result(root, "FIX_REQUIRED", first, current, initial, review, last, warnings);
      current = capture.capture(root, deadline);
      warnings.addAll(current.warnings());
      if (!same(first, current))
        return result(root, "PATCH_CHANGED", first, current, initial, review, last, warnings);
      last = tester.test(root, request.testCommand(), remaining(deadline, seconds));
      RunCancellation.propagate(null);
      if (!passed(last))
        return result(root, "FINAL_TEST_FAILED", first, current, initial, review, last, warnings);
      current = capture.capture(root, deadline);
      RunCancellation.propagate(null);
      remaining(deadline, 1);
      warnings.addAll(current.warnings());
      return result(
          root,
          same(first, current) ? "VERIFIED_CHECKS" : "PATCH_CHANGED",
          first,
          current,
          initial,
          review,
          last,
          warnings);
    } catch (IOException error) {
      RunCancellation.propagate(error);
      warnings.add("Patch inspection unavailable or shared cycle budget exhausted");
      return result(root, "BLOCKED", first, current, initial, review, last, warnings);
    }
  }

  /** 共通の実行基盤で用いる残量を返す。 */
  static Duration remaining(long deadline, int seconds) throws IOException {
    RunCancellation.propagate(null);
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new IOException("Cycle budget exhausted");
    return Duration.ofNanos(Math.min(nanos, Duration.ofSeconds(seconds).toNanos()));
  }

  /** 共通の実行基盤において、同一に該当するか判定する。 */
  private static boolean same(Snapshot first, Snapshot current) {
    return current.complete() && first.version().equals(current.version());
  }

  /** 共通の実行基盤において、passedに該当するか判定する。 */
  private static boolean passed(TestObservation test) {
    return test != null
        && test.status().equals("completed")
        && Objects.equals(test.exitCode(), 0)
        && !test.timedOut()
        && !test.logsTruncated();
  }

  /** 共通の実行基盤で用いる結果を返す。 */
  private static Result result(
      Path root,
      String status,
      Snapshot first,
      Snapshot current,
      TestObservation initial,
      Review review,
      TestObservation last,
      Set<String> warnings) {
    var actions =
        switch (status) {
          case "VERIFIED_CHECKS" -> List.<String>of();
          case "FIX_REQUIRED" ->
              List.of(
                  "Fix the reported patch findings using the existing editing tools, then invoke the complete self-review cycle again");
          case "INITIAL_TEST_FAILED", "FINAL_TEST_FAILED" ->
              List.of(
                  "Inspect the test diagnosis, fix or clarify the failure, then repeat the complete cycle");
          case "PATCH_CHANGED" ->
              List.of(
                  "The patch changed or became incomplete during verification; repeat the complete cycle on the current worktree");
          case "NO_PATCH" -> List.of("Implement a patch before requesting this verification cycle");
          default ->
              List.of(
                  "Inspect the incomplete review or repository constraints before requesting another cycle");
        };
    return new Result(
        root.toString(),
        status,
        first == null ? "" : first.version(),
        current == null ? "" : current.version(),
        first == null ? List.of() : first.changedFiles(),
        initial,
        review,
        last,
        actions,
        List.copyOf(warnings));
  }
}
