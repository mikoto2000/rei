package dev.mikoto2000.rei.core.completion;

/**
 * 入力候補の提示で受け渡すパス断片を表す。
 *
 * <p>Lexical path handling, independently testable for either platform.
 *
 * @param parent 親
 * @param prefix 接頭辞
 * @param separator 区切り
 */
public record PathFragment(String parent, String prefix, String separator) {
  /**
   * 入力候補の提示で用いる展開Homeを返す。
   *
   * <p>Completion inserts an absolute path because command execution does not expand '~'.
   */
  public static String expandHome(String token, java.nio.file.Path home, boolean windows) {
    if (token.equals("~") || token.startsWith("~/") || windows && token.startsWith("~\\")) {
      String separator = token.startsWith("~\\") ? "\\" : "/";
      return home.toString().replace(java.io.File.separator, separator)
          + (token.length() == 1 ? separator : token.substring(1));
    }
    return token;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static PathFragment parse(String value, boolean windows) {
    int slash = value.lastIndexOf('/');
    int backslash = windows ? value.lastIndexOf('\\') : -1;
    int split = Math.max(slash, backslash);
    String separator = split < 0 ? (windows ? "\\" : "/") : value.substring(split, split + 1);
    // Do not reinterpret a drive-relative path (C:foo) as a child of the current project.
    if (split < 0 && windows && value.matches("^[a-zA-Z]:.*"))
      return new PathFragment(value.substring(0, 2), value.substring(2), separator);
    return new PathFragment(value.substring(0, split + 1), value.substring(split + 1), separator);
  }
}
