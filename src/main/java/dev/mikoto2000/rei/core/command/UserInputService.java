package dev.mikoto2000.rei.core.command;

/**
 * 利用者入力のユースケースを実現する。
 *
 * <p>Canonical, UI-independent interpretation of input as established by the Shell.
 */
public final class UserInputService {

  /** 共通の実行基盤における種類の選択肢。 */
  public enum Kind {
    /** 空。 */
    EMPTY,
    /** チャット。 */
    CHAT,
    /** 終了。 */
    EXIT,
    /** 使い方。 */
    HELP,
    /** 形式のバージョン。 */
    VERSION,
    /** 貼り付けによる入力。 */
    PASTE,
    /** コマンド。 */
    COMMAND
  }

  /** 組み込みツールの結果配信方式。 */
  private static final java.util.Map<String, Kind> BUILTINS =
      java.util.Map.of(
          "exit",
          Kind.EXIT,
          "quit",
          Kind.EXIT,
          "help",
          Kind.HELP,
          "version",
          Kind.VERSION,
          "paste",
          Kind.PASTE);

  /** 共通の実行基盤で用いるbuiltinsを返す。 */
  public static java.util.Map<String, Kind> builtins() {
    return BUILTINS;
  }

  /**
   * 共通の実行基盤で受け渡す入力を表す。
   *
   * @param kind 種類
   * @param text テキスト
   * @param arguments 引数
   */
  public record Input(Kind kind, String text, String[] arguments) {
    /** 受け取った情報からInputを作成する。 */
    public Input {
      arguments = arguments.clone();
    }

    /** 共通の実行基盤で用いる引数を返す。 */
    @Override
    public String[] arguments() {
      return arguments.clone();
    }
  }

  /** 入力の解釈。 */
  private final UserInputParser parser;

  /** 指定された値や利用先を使って、UserInputServiceを初期化する。 */
  public UserInputService(UserInputParser parser) {
    this.parser = parser;
  }

  /** 共通の実行基盤で用いるinterpretを返す。 */
  public Input interpret(String value) {
    UserInputParser.ParsedInput parsed = parser.parse(value);
    if (parsed.kind() == UserInputParser.Kind.EMPTY) {
      return input(Kind.EMPTY, "", new String[0]);
    }
    if (parsed.kind() == UserInputParser.Kind.CHAT) {
      return input(Kind.CHAT, parsed.text(), new String[0]);
    }
    String[] arguments = parsed.arguments();
    if (arguments.length == 0) {
      return input(Kind.EMPTY, "", arguments);
    }
    if (arguments.length == 1) {
      Kind special = BUILTINS.getOrDefault(arguments[0], Kind.COMMAND);
      return input(special, parsed.text(), arguments);
    }
    return input(Kind.COMMAND, parsed.text(), arguments);
  }

  /** 共通の実行基盤で用いる入力を返す。 */
  private Input input(Kind kind, String text, String[] arguments) {
    return new Input(kind, text, arguments);
  }
}
