package dev.mikoto2000.rei.externalagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * 外部エージェントへの委譲で受け渡す初学者レビュー要求を表す。
 *
 * <p>Explicit local review grammar; all paths remain data until checked against the captured
 * Project.
 *
 * @param root 作業ルート
 * @param entry 項目
 * @param order 順序
 * @param audience 想定する読者
 * @param prerequisites 前提条件
 * @param os 実行するOS
 * @param shell シェル
 * @param mode 動作モード
 * @param format 形式
 */
public record BeginnerReviewRequest(
    String root,
    String entry,
    List<String> order,
    String audience,
    Set<String> prerequisites,
    String os,
    String shell,
    Mode mode,
    Format format) {
  /** 外部エージェントへの委譲における動作モードの選択肢。 */
  public enum Mode {
    /** 固定の定義を利用する方式。 */
    STATIC,
    /** 言語モデル。 */
    LLM
  }

  /** 外部エージェントへの委譲における形式の選択肢。 */
  public enum Format {
    /** テキスト。 */
    TEXT,
    /** 保存や受け渡しに用いるデータ形式の変換。 */
    JSON
  }

  /** 外部エージェントへの委譲に必要な選択可能な設定を扱う。 */
  @Command(name = "material-review")
  static final class Options {
    /** 評価する観点。 */
    @Parameters(index = "0")
    String perspective;

    /** 作業ルート。 */
    @Option(names = "--root", defaultValue = ".")
    String root;

    /** 項目。 */
    @Option(names = "--entry", required = true)
    String entry;

    /** 章の構成。 */
    @Option(names = "--chapter")
    List<String> chapters = new ArrayList<>();

    /** 想定する読者。 */
    @Option(names = "--audience", defaultValue = "初学者")
    String audience;

    /** 前提条件。 */
    @Option(names = "--prerequisite")
    List<String> prerequisites = new ArrayList<>();

    /** 実行するOS。 */
    @Option(names = "--os", defaultValue = "未指定")
    String os;

    /** シェル。 */
    @Option(names = "--shell", defaultValue = "未指定")
    String shell;

    /** 動作モード。 */
    @Option(names = "--mode", defaultValue = "STATIC")
    Mode mode;

    /** 形式。 */
    @Option(names = "--format", defaultValue = "TEXT")
    Format format;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static BeginnerReviewRequest parse(String[] arguments) {
    if (arguments == null
        || arguments.length > 2048
        || Arrays.stream(arguments).mapToInt(String::length).sum() > 32768)
      throw new IllegalArgumentException("Material review input exceeds limit");
    var options = new Options();
    try {
      new CommandLine(options).setCaseInsensitiveEnumValuesAllowed(true).parseArgs(arguments);
    } catch (CommandLine.ParameterException error) {
      throw new IllegalArgumentException(
          "Usage: /material-review beginner --root DIR --entry PAGE [--chapter PAGE] [--audience TEXT] [--prerequisite CONCEPT] [--os OS] [--shell SHELL] [--mode static|llm] [--format text|json]");
    }
    if (!"beginner".equals(options.perspective)
        || options.root.isBlank()
        || options.entry.isBlank())
      throw new IllegalArgumentException("Beginner perspective, material root and entry required");
    return new BeginnerReviewRequest(
        options.root,
        options.entry,
        List.copyOf(options.chapters),
        options.audience,
        Set.copyOf(options.prerequisites),
        options.os,
        options.shell,
        options.mode,
        options.format);
  }

  /** 外部エージェントへの委譲で判断に用いるacceptsの条件を確認する。 */
  public static boolean accepts(String text) {
    return text != null
        && (text.equals("/material-review") || text.startsWith("/material-review "));
  }

  /** 入力を機能内で扱えるよう、テキストを解釈する。 */
  public static BeginnerReviewRequest parseText(String text) {
    if (!accepts(text)) throw new IllegalArgumentException("Material review command required");
    var words = new dev.mikoto2000.rei.core.command.UserInputParser().split(text);
    return parse(Arrays.copyOfRange(words, 1, words.length));
  }
}
