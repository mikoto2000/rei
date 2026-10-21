package dev.mikoto2000.rei.core.process;

import dev.mikoto2000.rei.event.CredentialRedactor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部プロセスの管理で受け渡す構築検証失敗情報診断を表す。
 *
 * <p>Deterministic observations of command output; suggestions never execute a command.
 *
 * @param outcome 最終結果
 * @param category 分類
 * @param partial 部分的な結果
 * @param failedTests 失敗検証一覧
 * @param causes 原因一覧
 * @param evidence 根拠
 * @param nextActions 次操作一覧
 * @param warnings 注意事項
 */
public record BuildTestFailureDiagnosis(
    String outcome,
    String category,
    boolean partial,
    List<String> failedTests,
    List<Evidence> causes,
    List<Evidence> evidence,
    List<String> nextActions,
    List<String> warnings) {
  /**
   * 外部プロセスの管理で受け渡す根拠を表す。
   *
   * @param source 取得元
   * @param type 種別
   * @param text テキスト
   */
  public record Evidence(String source, String type, String text) {}

  /** 最大取得元。 */
  private static final int MAX_SOURCE = 65536;

  /** MAVEN検証。 */
  private static final Pattern MAVEN_TEST =
      Pattern.compile(
          "^\\s*(?:\\[ERROR]\\s*)?([\\w.$#]+)(?::\\d+)?\\s+.*<<<\\s*(?:FAILURE|ERROR)!");

  /** GRADLE検証。 */
  private static final Pattern GRADLE_TEST =
      Pattern.compile("^\\s*([\\w.$]+)\\s*>\\s*([\\w$]+(?:\\([^)]*\\))?)\\s+FAILED(?:\\s.*)?$");

  /** 検証件数。 */
  private static final Pattern TEST_COUNTS = Pattern.compile("(?:Failures|Errors):\\s*[1-9]\\d*");

  /** コンパイル。 */
  private static final Pattern COMPILE =
      Pattern.compile(
          "(?:COMPILATION ERROR|\\.java:\\[\\d+,\\d+]|\\.java:\\d+(?::\\d+)?:\\s*error:)",
          Pattern.CASE_INSENSITIVE);

  /** 依存条件。 */
  private static final Pattern DEPENDENCY =
      Pattern.compile(
          "Could not (?:resolve (?:dependencies|artifact|all files|all dependencies)|transfer artifact|find artifact)",
          Pattern.CASE_INSENSITIVE);

  /** 例外。 */
  private static final Pattern EXCEPTION =
      Pattern.compile("\\b(?:[a-zA-Z_$][\\w$]*\\.)*[A-Z_$][\\w$]*(?:Exception|Error)\\b");

  /** 受け取った情報からBuildTestFailureDiagnosisを作成する。 */
  public BuildTestFailureDiagnosis {
    failedTests = List.copyOf(failedTests);
    causes = List.copyOf(causes);
    evidence = List.copyOf(evidence);
    nextActions = List.copyOf(nextActions);
    warnings = List.copyOf(warnings);
  }

  /** 外部プロセスの管理で用いるコマンドを返す。 */
  public static BuildTestFailureDiagnosis command(
      String status,
      Integer exitCode,
      boolean timedOut,
      String stdout,
      String stderr,
      String error) {
    return analyze(status, exitCode, timedOut, true, stdout, stderr, error);
  }

  /** 外部プロセスの管理で用いるプロセスを返す。 */
  public static BuildTestFailureDiagnosis process(
      BackgroundProcessStatus status,
      Integer exitCode,
      boolean found,
      List<String> stdout,
      List<String> stderr,
      String message) {
    return analyze(
        status == null ? null : status.name(),
        exitCode,
        false,
        found,
        join(stdout),
        join(stderr),
        message);
  }

  /** 外部プロセスの管理で用いる統合を返す。 */
  private static String join(List<String> lines) {
    if (lines == null) return "";
    int size = 0;
    for (String line : lines) {
      if (line != null) size += Math.min(line.length(), MAX_SOURCE + 1) + 1;
      if (size > MAX_SOURCE) return "x".repeat(MAX_SOURCE + 1);
    }
    return String.join("\n", lines.stream().map(s -> Objects.toString(s, "")).toList());
  }

  /** 外部プロセスの管理で用いる分析を返す。 */
  private static BuildTestFailureDiagnosis analyze(
      String status,
      Integer exitCode,
      boolean timedOut,
      boolean found,
      String out,
      String err,
      String message) {
    String state = Objects.toString(status, "").toUpperCase(Locale.ROOT);
    String outcome =
        !found
            ? "UNKNOWN"
            : timedOut
                ? "TIMED_OUT"
                : Set.of("KILLED", "CANCELLED").contains(state)
                    ? "CANCELLED"
                    : Set.of("RUNNING", "STARTING").contains(state)
                        ? "RUNNING"
                        : exitCode != null
                            ? (exitCode == 0 ? "SUCCEEDED" : "FAILED")
                            : state.equals("FAILED") ? "FAILED" : "UNKNOWN";
    var scan = new Scan();
    scan.read("stdout", out);
    scan.read("stderr", err);
    scan.read("message", message);
    String category =
        scan.test
            ? "TEST_FAILURE"
            : scan.compile
                ? "COMPILATION_FAILURE"
                : scan.dependency
                    ? "DEPENDENCY_FAILURE"
                    : scan.exception
                        ? "EXCEPTION_REPORTED"
                        : outcome.equals("SUCCEEDED") ? "NONE" : "UNKNOWN";
    if (!category.equals("NONE") && !category.equals("UNKNOWN") && outcome.equals("SUCCEEDED"))
      scan.warnings.add(
          "Failure text was reported with exit code 0; verify whether this was an expected negative test");
    if (outcome.equals("RUNNING"))
      scan.warnings.add("Process has not finished; diagnostic observations are provisional");
    if (!found)
      scan.warnings.add(
          "Process was not found; its completion state and complete logs are unavailable");
    if (!category.equals("NONE"))
      scan.warnings.add(
          "Reported log observations only; root cause is not independently verified and logs may be incomplete");
    List<String> actions =
        switch (outcome) {
          case "RUNNING" ->
              List.of(
                  "Wait for the managed process to finish and inspect its terminal status before retrying");
          case "TIMED_OUT" ->
              List.of("Inspect progress and the timeout budget before requesting a bounded retry");
          case "CANCELLED" ->
              List.of("Confirm whether cancellation was intended before requesting a restart");
          default ->
              switch (category) {
                case "TEST_FAILURE" ->
                    List.of(
                        "Inspect the reported failed test and assertion evidence",
                        "Reproduce the smallest relevant test before making a fix");
                case "COMPILATION_FAILURE" ->
                    List.of(
                        "Inspect the reported source location and compiler diagnostic",
                        "Check type names, imports and compiler configuration before rebuilding");
                case "DEPENDENCY_FAILURE" ->
                    List.of(
                        "Check the reported dependency, repository access and local/offline cache",
                        "Verify configuration before retrying; do not infer a code defect from dependency resolution alone");
                case "EXCEPTION_REPORTED" ->
                    List.of(
                        "Inspect the reported cause chain and the corresponding caller",
                        "Reproduce the exception before choosing a repair");
                case "NONE" -> List.of();
                default ->
                    List.of(
                        "Inspect complete stdout/stderr and process status; available observations do not identify the cause");
              };
        };
    return new BuildTestFailureDiagnosis(
        outcome,
        category,
        scan.partial,
        List.copyOf(scan.tests),
        scan.causes,
        scan.evidence,
        actions,
        List.copyOf(scan.warnings));
  }

  /** 外部プロセスの管理に必要な調査を扱う。 */
  private static final class Scan {
    /** 部分的な結果／検証／コンパイル／依存条件／例外。 */
    boolean partial, test, compile, dependency, exception;

    /** 検証一覧／注意事項。 */
    final Set<String> tests = new LinkedHashSet<>(), warnings = new LinkedHashSet<>();

    /** 原因一覧／根拠。 */
    final List<Evidence> causes = new ArrayList<>(), evidence = new ArrayList<>();

    /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    void read(String source, String text) {
      if (text == null || text.isEmpty()) return;
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (text.length() > MAX_SOURCE) {
        partial = true;
        warnings.add("Oversized log source omitted from diagnosis; inspect the complete log");
        return;
      }
      String safe =
          CredentialRedactor.redact(text)
              .replaceAll("(?s)-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----.*", "[REDACTED]");
      var lines = safe.lines().iterator();
      int count = 0;
      while (lines.hasNext()) {
        if (++count > 2000) {
          partial = true;
          warnings.add("Diagnostic line scan limit reached");
          break;
        }
        String line = lines.next();
        if (line.length() > 8192) {
          partial = true;
          warnings.add("Oversized log line omitted");
          continue;
        }
        String type = null;
        var maven = MAVEN_TEST.matcher(line);
        var gradle = GRADLE_TEST.matcher(line);
        if (maven.find() && !line.contains("Tests run:")) {
          test = true;
          addTest(maven.group(1));
          type = "FAILED_TEST";
        } else if (gradle.find()) {
          test = true;
          addTest(gradle.group(1) + "#" + gradle.group(2));
          type = "FAILED_TEST";
        } else if (line.contains("Tests run:") && TEST_COUNTS.matcher(line).find()
            || line.contains("There were failing tests")) {
          test = true;
          type = "TEST_FAILURE_SUMMARY";
        } else if (COMPILE.matcher(line).find()) {
          compile = true;
          type = "COMPILER";
        } else if (DEPENDENCY.matcher(line).find()) {
          dependency = true;
          type = "DEPENDENCY";
        } else if (line.stripLeading().startsWith("Caused by:")) {
          exception = true;
          type = "REPORTED_CAUSE";
        } else if (EXCEPTION.matcher(line).find()) {
          exception = true;
          type = "EXCEPTION";
        }
        if (type != null) {
          var item = new Evidence(source, type, line.substring(0, Math.min(line.length(), 512)));
          if (line.length() > 512) {
            partial = true;
            warnings.add("Diagnostic excerpt clipped");
          }
          if (evidence.size() < 24) evidence.add(item);
          else {
            partial = true;
            warnings.add("Diagnostic evidence limited to 24 observations");
          }
          if (type.equals("REPORTED_CAUSE")) {
            if (causes.size() == 8) {
              causes.removeFirst();
              partial = true;
              warnings.add("Reported causes limited to the latest 8 observations");
            }
            causes.add(item);
          }
        }
      }
    }

    /** 新たな対象を管理できるよう、検証を追加する。 */
    void addTest(String name) {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (name.length() > 256) {
        partial = true;
        warnings.add("Reported test name omitted because it exceeds the limit");
      } else if (tests.size() < 12) tests.add(name);
      else if (!tests.contains(name)) {
        partial = true;
        warnings.add("Reported tests limited to 12 names");
      }
    }
  }
}
