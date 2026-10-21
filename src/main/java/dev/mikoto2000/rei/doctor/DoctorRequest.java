package dev.mikoto2000.rei.doctor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * 実行環境の診断で受け渡す環境診断要求を表す。
 *
 * <p>Bounded explicit diagnostic grammar: no URL, executable or prompt supplied by users.
 *
 * @param checks 確認結果
 * @param details 詳細一覧
 */
public record DoctorRequest(List<Check> checks, boolean details) {
  /** 実行環境の診断における確認の選択肢。 */
  public enum Check {
    /** 接続状態を確認する診断項目。 */
    CONNECTIVITY,
    /** 推定。 */
    INFERENCE,
    /** Codexの設定または実行の窓口。 */
    CODEX,
    /** Claudeの設定または実行の窓口。 */
    CLAUDE,
    /** マイク。 */
    MICROPHONE
  }

  /** 受け取った情報からDoctorRequestを作成する。 */
  public DoctorRequest {
    checks = List.copyOf(checks);
  }

  /** 実行環境の診断に必要な選択可能な設定を扱う。 */
  static class Options {
    /** 確認結果。 */
    @Option(names = "--check")
    List<Check> checks = new ArrayList<>();

    /** 詳細一覧。 */
    @Option(names = "--details")
    boolean details;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static DoctorRequest parse(String[] args) {
    if (args == null
        || args.length > 32
        || Arrays.stream(args).anyMatch(Objects::isNull)
        || Arrays.stream(args).mapToInt(String::length).sum() > 4096)
      throw new IllegalArgumentException("Doctor input exceeds limit");
    var options = new Options();
    try {
      new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(args);
    } catch (CommandLine.ParameterException invalid) {
      throw new IllegalArgumentException(
          "Usage: /doctor [--details] [--check connectivity|inference|codex|claude|microphone]");
    }
    return new DoctorRequest(options.checks.stream().distinct().toList(), options.details);
  }

  /** 実行環境の診断で判断に用いるacceptsの条件を確認する。 */
  public static boolean accepts(String text) {
    return text != null && (text.equals("/doctor") || text.startsWith("/doctor "));
  }

  /** 入力を機能内で扱えるよう、テキストを解釈する。 */
  public static DoctorRequest parseText(String text) {
    if (!accepts(text)) throw new IllegalArgumentException("Doctor command required");
    var args = new dev.mikoto2000.rei.core.command.UserInputParser().split(text);
    return parse(Arrays.copyOfRange(args, 1, args.length));
  }

  /** 依頼を進めるための候補や手順を組み立てる。 */
  public String plan() {
    return "Active diagnosis requested: "
        + checks
        + ". connectivity: unauthenticated GET /v1/models; inference: one bounded fixed prompt; codex/claude: configured CLI --version only; microphone: acquire and close without starting recording. Only requested checks run, after existing permission checks.";
  }
}
