package dev.mikoto2000.rei.core;

import java.util.regex.Pattern;

/**
 * 共通の実行基盤に必要な検索パスの照合規則を扱う。
 *
 * <p>Platform-independent globs for paths relative to the search base directory.
 */
final class SearchGlob {
  /** 照合規則。 */
  private final Pattern pattern;

  /** ファイル名限定。 */
  private final boolean fileNameOnly;

  /** 指定された値や利用先を使って、SearchGlobを初期化する。 */
  private SearchGlob(Pattern pattern, boolean fileNameOnly) {
    this.pattern = pattern;
    this.fileNameOnly = fileNameOnly;
  }

  /** 共通の実行基盤で用いるコンパイルを返す。 */
  static SearchGlob compile(String glob) {
    if (glob == null || glob.isBlank()) return null;
    String normalized = glob.replace('\\', '/');
    StringBuilder regex = new StringBuilder();
    boolean inAlternatives = false;
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      switch (c) {
        case '*' -> {
          if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '*') {
            i++;
            if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '/') {
              regex.append("(?:[^/]+/)*");
              i++;
            } else {
              regex.append(".*");
            }
          } else {
            regex.append("[^/]*");
          }
        }
        case '?' -> regex.append("[^/]");
        case '{' -> {
          if (inAlternatives)
            throw new IllegalArgumentException("Nested glob alternatives: " + glob);
          inAlternatives = true;
          regex.append("(?:");
        }
        case '}' -> {
          if (!inAlternatives) throw new IllegalArgumentException("Unmatched glob brace: " + glob);
          inAlternatives = false;
          regex.append(')');
        }
        case ',' -> regex.append(inAlternatives ? "|" : ",");
        case '[' -> {
          int end = normalized.indexOf(']', i + 1);
          if (end < 0) throw new IllegalArgumentException("Unclosed glob character class: " + glob);
          String members = normalized.substring(i + 1, end);
          regex.append("[[");
          if (members.startsWith("!")) {
            regex.append('^');
            members = members.substring(1);
          }
          // Quote regex-only class operators; retain glob ranges such as a-z.
          for (int j = 0; j < members.length(); j++) {
            char member = members.charAt(j);
            if (member == '[' || member == '&' || member == '^') regex.append('\\');
            regex.append(member);
          }
          regex.append("]&&[^/]]");
          i = end;
        }
        default -> regex.append(Pattern.quote(String.valueOf(c)));
      }
    }
    if (inAlternatives) throw new IllegalArgumentException("Unclosed glob alternatives: " + glob);
    return new SearchGlob(Pattern.compile(regex.toString()), !normalized.contains("/"));
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  boolean matches(String relativePath) {
    String normalized = relativePath.replace('\\', '/');
    String target =
        fileNameOnly ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
    return pattern.matcher(target).matches();
  }
}
