package dev.mikoto2000.rei.subagent;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 補助エージェントへの委譲に必要な外部取得元Redactorを扱う。
 *
 * <p>Redacts only external document snapshots; original files and the event redactor are untouched.
 */
public final class ExternalSourceRedactor {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExternalSourceRedactor() {}

  /** 目印。 */
  private static final String MARKER = "[REDACTED]";

  /** キー一覧。 */
  private static final String KEYS =
      "api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token(?:[_-]?hint)?|code[_-]?verifier|token|password|secret|credential|authorization|accessJwt|refreshJwt|private[_-]?key";

  /** 照合キー。 */
  private static final String KEY = "(?:[A-Za-z0-9_.-]*)(?:" + KEYS + ")";

  /** 引用済みキー。 */
  private static final String QUOTED_KEY = "(?:[A-Za-z0-9_.-]|\\\\u[0-9a-fA-F]{4}){1,256}";

  /** 秘密を含むキー。 */
  private static final Pattern SENSITIVE_KEY = Pattern.compile(KEY, Pattern.CASE_INSENSITIVE);

  /** UNICODE特殊文字の保護。 */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

  // Horizontal whitespace is deliberate: an empty value must not consume the following line.
  /** 代入の記述を見つける照合規則。 */
  private static final Pattern ASSIGNMENT =
      Pattern.compile(
          "(?:\"(?<doubleKey>"
              + QUOTED_KEY
              + ")\"|'(?<singleKey>"
              + QUOTED_KEY
              + ")'|(?<![A-Za-z0-9_.-])"
              + KEY
              + ")[ \\t]*[:=][ \\t]*",
          Pattern.CASE_INSENSITIVE);

  /** 非公開キー開始。 */
  private static final Pattern PRIVATE_KEY_BEGIN =
      Pattern.compile("-----BEGIN ([A-Z ]*PRIVATE KEY)-----");

  /** APIトークン。 */
  private static final Pattern API_TOKEN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}\\b");

  /** Slackトークン。 */
  private static final Pattern SLACK_TOKEN = Pattern.compile("\\bx(?:ox[baprs]|app)-[A-Za-z0-9-]+");

  /** 秘密を含むJWTを検出する規則。 */
  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  /** 認証通信方式。 */
  private static final Pattern AUTH_SCHEME =
      Pattern.compile(
          "(?i)\\b(Bearer|Basic)([ \\t]+)(\\[REDACTED\\]|<[^>\\r\\n]+>|[A-Za-z0-9+/=_~.:-]+)");

  /** 認証値接頭辞。 */
  private static final Pattern AUTH_VALUE_PREFIX =
      Pattern.compile("[ \\t]*(?:Bearer|Basic)[ \\t]+", Pattern.CASE_INSENSITIVE);

  /** パスワード文字コード接頭辞。 */
  private static final Pattern PASSWORD_ENCODING_PREFIX =
      Pattern.compile(
          "\\{(?:noop|bcrypt|argon2(?:id)?|pbkdf2|scrypt|sha256)\\}(?=\\S)",
          Pattern.CASE_INSENSITIVE);

  /**
   * 補助エージェントへの委譲で受け渡す結果を表す。
   *
   * @param content 内容
   * @param redacted 秘密情報を除いた内容
   */
  public record Result(String content, boolean redacted) {}

  /**
   * 補助エージェントへの委譲で受け渡すAssignment結果を表す。
   *
   * @param content 内容
   * @param unhandled 未処理
   */
  private record AssignmentResult(String content, boolean unhandled) {}

  /**
   * 補助エージェントへの委譲で受け渡す値を表す。
   *
   * @param end 終了
   * @param replacement 置換
   * @param unmasked 元の内容
   */
  private record Value(int end, String replacement, boolean unmasked) {}

  /**
   * 補助エージェントへの委譲で用いるredactを返す。
   *
   * <p>The backend bounds source bytes before this deterministic, local transformation.
   */
  public static Result redact(String content) {
    Objects.requireNonNull(content);
    String safe = redactPrivateKeys(content);
    safe = redactAssignments(safe).content();
    safe = API_TOKEN.matcher(safe).replaceAll(MARKER);
    safe = SLACK_TOKEN.matcher(safe).replaceAll(MARKER);
    safe = JWT.matcher(safe).replaceAll(MARKER);
    var auth = AUTH_SCHEME.matcher(safe);
    var result = new StringBuilder();
    while (auth.find())
      auth.appendReplacement(
          result, java.util.regex.Matcher.quoteReplacement(auth.group(1) + auth.group(2) + MARKER));
    auth.appendTail(result);
    safe = result.toString();
    return new Result(safe, !safe.equals(content) || safe.contains(MARKER));
  }

  /**
   * 補助エージェントへの委譲において、元の内容認証情報の有無を判定する。
   *
   * <p>Existing exact markers are safe regardless of quote style; unsupported values remain
   * refused.
   */
  public static boolean hasUnmaskedCredentials(String content) {
    Objects.requireNonNull(content);
    if (PRIVATE_KEY_BEGIN.matcher(content).find()
        || API_TOKEN.matcher(content).find()
        || SLACK_TOKEN.matcher(content).find()
        || JWT.matcher(content).find()) return true;
    var assignment = redactAssignments(content);
    if (assignment.unhandled() || !assignment.content().equals(content)) return true;
    var auth = AUTH_SCHEME.matcher(content);
    while (auth.find())
      if (!MARKER.equals(auth.group(3)) || !standaloneMarkerTail(content, auth.end())) return true;
    return false;
  }

  /** 補助エージェントへの委譲で用いるredact非公開キー一覧を返す。 */
  private static String redactPrivateKeys(String content) {
    var begin = PRIVATE_KEY_BEGIN.matcher(content);
    var result = new StringBuilder();
    int cursor = 0;
    while (begin.find(cursor)) {
      result.append(content, cursor, begin.start());
      String endMarker = "-----END " + begin.group(1) + "-----";
      int closing = content.indexOf(endMarker, begin.end());
      int end = closing < 0 ? content.length() : closing + endMarker.length();
      result.append(maskKeepingNewlines(content.substring(begin.start(), end)));
      cursor = end;
    }
    result.append(content, cursor, content.length());
    return result.toString();
  }

  /** 補助エージェントへの委譲で用いるredactAssignmentsを返す。 */
  private static AssignmentResult redactAssignments(String content) {
    var matcher = ASSIGNMENT.matcher(content);
    var result = new StringBuilder();
    int cursor = 0;
    boolean unhandled = false;
    while (matcher.find(cursor)) {
      int start = matcher.end();
      String quoted =
          matcher.group("doubleKey") == null
              ? matcher.group("singleKey")
              : matcher.group("doubleKey");
      if (quoted != null && !SENSITIVE_KEY.matcher(decodeKey(quoted)).matches()) {
        result.append(content, cursor, start);
        cursor = start;
        continue;
      }
      Value value = value(content, start, matcher.start(), matcher.group());
      // Markdown lists can describe several password cases in prose rather than a single
      // scalar. Hide the entire list value, never just its first whitespace-delimited token.
      if (value != null
          && value.unmasked()
          && value.replacement() == null
          && plainMarkdownListValue(content, matcher.start(), matcher.group()))
        value = new Value(lineEnd(content, start), MARKER, true);
      result.append(content, cursor, start);
      if (value == null) {
        cursor = start;
        continue;
      }
      if (value.unmasked() && value.replacement() == null) unhandled = true;
      result.append(
          value.replacement() == null
              ? content.substring(start, value.end())
              : value.replacement());
      cursor = value.end();
    }
    result.append(content, cursor, content.length());
    return new AssignmentResult(result.toString(), unhandled);
  }

  /** 受信した内容を利用できるよう、照合キーを復元する。 */
  private static String decodeKey(String key) {
    var escape = UNICODE_ESCAPE.matcher(key);
    var result = new StringBuilder();
    int cursor = 0;
    while (escape.find()) {
      result.append(key, cursor, escape.start());
      result.append((char) Integer.parseInt(escape.group(1), 16));
      cursor = escape.end();
    }
    return result.append(key, cursor, key.length()).toString();
  }

  /** 補助エージェントへの委譲において、装飾のないMarkdown一覧値に該当するか判定する。 */
  private static boolean plainMarkdownListValue(String content, int keyStart, String prefix) {
    if (!prefix.stripTrailing().endsWith(":") || jsonContext(content, keyStart, prefix))
      return false;
    int lineStart = content.lastIndexOf('\n', Math.max(0, keyStart - 1)) + 1;
    int marker = lineStart;
    while (marker < keyStart && (content.charAt(marker) == ' ' || content.charAt(marker) == '\t'))
      marker++;
    return marker + 1 < keyStart
        && "-*+".indexOf(content.charAt(marker)) >= 0
        && (content.charAt(marker + 1) == ' ' || content.charAt(marker + 1) == '\t');
  }

  /** 後続の処理で使う値を返す。 */
  private static Value value(String content, int start, int keyStart, String prefix) {
    if (start >= content.length() || content.charAt(start) == '\r' || content.charAt(start) == '\n')
      return null;
    if (content.startsWith(MARKER, start)
        && safeMarkerTail(content, start + MARKER.length(), keyStart, prefix)) {
      int end = start + MARKER.length();
      return new Value(end, null, ambiguousTail(content, end, keyStart, prefix, false));
    }
    char first = content.charAt(start);
    if (first == '\\') {
      Value escaped = escapedQuotedValue(content, start);
      if (escaped != null) return escaped;
    }
    if (first == '\"' || first == '\'') {
      int end = start + 1;
      boolean closed = false;
      while (end < content.length()) {
        char next = content.charAt(end++);
        if (next == '\\' && end < content.length()) end++;
        else if (next == first) {
          closed = true;
          break;
        }
      }
      // Incomplete quoted examples are bounded to their original line, keeping its opening quote.
      if (!closed) end = lineEnd(content, start + 1);
      int insideEnd = closed ? end - 1 : end;
      String inside = content.substring(start + 1, insideEnd);
      if (masked(inside)) return new Value(end, null, false);
      return new Value(
          end, first + maskValue(inside) + (closed ? String.valueOf(first) : ""), true);
    }
    if (first == '|' || first == '>') {
      Value block = blockValue(content, start, keyStart);
      if (block != null) return block;
    }
    if (first == '$' && start + 1 < content.length() && content.charAt(start + 1) == '{') {
      int end = start + 2, depth = 1;
      while (end < content.length()
          && content.charAt(end) != '\r'
          && content.charAt(end) != '\n'
          && depth > 0) {
        char next = content.charAt(end++);
        if (next == '{') depth++;
        else if (next == '}') depth--;
      }
      return new Value(end, depth == 0 ? MARKER : null, true);
    }
    return bareValue(content, start, keyStart, prefix);
  }

  /** 補助エージェントへの委譲で用いるbare値を返す。 */
  private static Value bareValue(String content, int start, int keyStart, String prefix) {
    boolean json = jsonContext(content, keyStart, prefix);
    char enclosure = enclosure(content, keyStart, prefix);
    var auth = AUTH_VALUE_PREFIX.matcher(content);
    auth.region(start, content.length());
    int credential = auth.lookingAt() ? auth.end() : start;
    String scheme = content.substring(start, credential);
    if (credential >= content.length()
        || content.charAt(credential) == '\r'
        || content.charAt(credential) == '\n') return new Value(credential, null, true);
    var encoding = PASSWORD_ENCODING_PREFIX.matcher(content);
    encoding.region(credential, content.length());
    if (encoding.lookingAt()) {
      scheme += encoding.group();
      credential = encoding.end();
    }
    if (content.startsWith(MARKER, credential)
        && safeMarkerTail(content, credential + MARKER.length(), keyStart, prefix)) {
      int end = credential + MARKER.length();
      return new Value(end, null, ambiguousTail(content, end, keyStart, prefix, false));
    }
    if (content.charAt(credential) == '{' || content.charAt(credential) == '[')
      return new Value(credential + 1, null, true);
    int end = credential;
    while (end < content.length()
        && !Character.isWhitespace(content.charAt(end))
        && (enclosure == 0 || content.charAt(end) != enclosure)
        && (!json || ",;{}[]()\"'".indexOf(content.charAt(end)) < 0)) end++;
    if (end == credential) return new Value(end + 1, null, true);
    String token = content.substring(credential, end);
    int contentEnd = token.length();
    // Only a trailing separator may be retained. An internal comma/semicolon is ambiguous
    // between a credential and adjacent code, so it must remain refused instead of partly masked.
    if (!json)
      while (contentEnd > 0 && ",;)]}".indexOf(token.charAt(contentEnd - 1)) >= 0) contentEnd--;
    String suffix = token.substring(contentEnd);
    if (contentEnd == 0
        || token.substring(0, contentEnd).indexOf(';') >= 0
        || token.substring(0, contentEnd).indexOf(',') >= 0
        || ambiguousTail(content, end, keyStart, prefix, !suffix.isEmpty()))
      return new Value(end, null, true);
    String replacement = scheme + MARKER;
    if (prefix.charAt(0) == '\"') replacement = "\"" + replacement + "\"";
    else if (prefix.charAt(0) == '\'') replacement = "'" + replacement + "'";
    return new Value(end, replacement + suffix, true);
  }

  /** 補助エージェントへの委譲において、JSON変換文脈に該当するか判定する。 */
  private static boolean jsonContext(String content, int keyStart, String prefix) {
    if (prefix.charAt(0) == '\"' || prefix.charAt(0) == '\'') return true;
    int previous = keyStart - 1;
    while (previous >= 0 && (content.charAt(previous) == ' ' || content.charAt(previous) == '\t'))
      previous--;
    return previous >= 0 && (content.charAt(previous) == '{' || content.charAt(previous) == ',');
  }

  /** 補助エージェントへの委譲で用いるenclosureを返す。 */
  private static char enclosure(String content, int keyStart, String prefix) {
    if (prefix.charAt(0) == '\"' || prefix.charAt(0) == '\'' || keyStart == 0) return 0;
    char previous = content.charAt(keyStart - 1);
    return previous == '\"' || previous == '\'' ? previous : 0;
  }

  /** 補助エージェントへの委譲において、ambiguous末尾に該当するか判定する。 */
  private static boolean ambiguousTail(
      String content, int end, int keyStart, String prefix, boolean separator) {
    if (separator
        || jsonContext(content, keyStart, prefix)
        || enclosure(content, keyStart, prefix) != 0) return false;
    int next = end;
    while (next < content.length() && (content.charAt(next) == ' ' || content.charAt(next) == '\t'))
      next++;
    return next > end
        && next < content.length()
        && content.charAt(next) != '\r'
        && content.charAt(next) != '\n'
        && content.charAt(next) != '#'
        && !content.startsWith("//", next);
  }

  /** 補助エージェントへの委譲において、安全性目印末尾に該当するか判定する。 */
  private static boolean safeMarkerTail(String content, int end, int keyStart, String prefix) {
    if (end >= content.length() || content.charAt(end) == '\r' || content.charAt(end) == '\n')
      return true;
    char next = content.charAt(end);
    if (next == ' ' || next == '\t') return !ambiguousTail(content, end, keyStart, prefix, false);
    if (jsonContext(content, keyStart, prefix) && ",;})]".indexOf(next) >= 0) return true;
    if (enclosure(content, keyStart, prefix) != 0 && next == enclosure(content, keyStart, prefix))
      return true;
    int cursor = end;
    while (cursor < content.length() && ",;)]}".indexOf(content.charAt(cursor)) >= 0) cursor++;
    return cursor > end
        && (cursor == content.length() || Character.isWhitespace(content.charAt(cursor)));
  }

  /** 補助エージェントへの委譲において、独立した目印末尾に該当するか判定する。 */
  private static boolean standaloneMarkerTail(String content, int end) {
    if (end >= content.length() || Character.isWhitespace(content.charAt(end))) return true;
    char next = content.charAt(end);
    if (",;)]}\"'".indexOf(next) >= 0) return true;
    return next == '.'
        && (end + 1 == content.length() || Character.isWhitespace(content.charAt(end + 1)));
  }

  /** 補助エージェントへの委譲で用いる特殊文字を保護した引用済み値を返す。 */
  private static Value escapedQuotedValue(String content, int start) {
    int quote = start;
    while (quote < content.length() && content.charAt(quote) == '\\') quote++;
    if (quote >= content.length()
        || (content.charAt(quote) != '\"' && content.charAt(quote) != '\'')) return null;
    int slashes = quote - start;
    char delimiter = content.charAt(quote);
    int search = quote + 1, closing = -1;
    while (search < content.length()) {
      int next = content.indexOf(delimiter, search);
      if (next < 0) break;
      int back = next;
      while (back > quote && content.charAt(back - 1) == '\\') back--;
      if (next - back == slashes) {
        closing = back;
        break;
      }
      search = next + 1;
    }
    int insideEnd = closing < 0 ? lineEnd(content, quote + 1) : closing;
    int end = closing < 0 ? insideEnd : closing + slashes + 1;
    String inside = content.substring(quote + 1, insideEnd);
    if (masked(inside)) return new Value(end, null, false);
    String frame = content.substring(start, quote + 1);
    return new Value(end, frame + maskValue(inside) + (closing < 0 ? "" : frame), true);
  }

  /** 補助エージェントへの委譲で用いるまとまり値を返す。 */
  private static Value blockValue(String content, int start, int keyStart) {
    int headerEnd = lineEnd(content, start);
    String header = content.substring(start, headerEnd);
    if (!header.matches("[|>][0-9+-]*[ \\t]*(?:#[^\\r\\n]*)?")) return null;
    int lineStart = content.lastIndexOf('\n', Math.max(0, keyStart - 1)) + 1;
    int indentation = indentation(content, lineStart, keyStart);
    int cursor = nextLine(content, headerEnd);
    int end = cursor;
    boolean secret = false, markerAdded = false;
    var result = new StringBuilder(content.substring(start, cursor));
    while (cursor < content.length()) {
      int currentEnd = lineEnd(content, cursor);
      int next = nextLine(content, currentEnd);
      String line = content.substring(cursor, currentEnd);
      int leading = indentation(content, cursor, currentEnd);
      if (!line.isBlank() && leading <= indentation) break;
      if (!line.isBlank()) {
        String text = line.substring(leading);
        secret |= !masked(text);
        result.append(line, 0, leading);
        if (!markerAdded) {
          result.append(MARKER);
          markerAdded = true;
        }
      } else result.append(line);
      result.append(content, currentEnd, next);
      cursor = next;
      end = next;
    }
    if (!secret) return new Value(end, null, false);
    return new Value(end, result.toString(), true);
  }

  /** 補助エージェントへの委譲において、非公開化した内容に該当するか判定する。 */
  private static boolean masked(String value) {
    if (MARKER.equals(value.trim())) return true;
    var auth = AUTH_VALUE_PREFIX.matcher(value);
    return auth.lookingAt() && MARKER.equals(value.substring(auth.end()).trim());
  }

  /** 補助エージェントへの委譲で用いる行終了を返す。 */
  private static int lineEnd(String content, int offset) {
    while (offset < content.length()
        && content.charAt(offset) != '\r'
        && content.charAt(offset) != '\n') offset++;
    return offset;
  }

  /** 補助エージェントへの委譲で用いる次行を返す。 */
  private static int nextLine(String content, int offset) {
    if (offset < content.length() && content.charAt(offset) == '\r') offset++;
    if (offset < content.length() && content.charAt(offset) == '\n') offset++;
    return offset;
  }

  /** 補助エージェントへの委譲で用いる字下げを返す。 */
  private static int indentation(String content, int start, int end) {
    int cursor = start;
    while (cursor < end && (content.charAt(cursor) == ' ' || content.charAt(cursor) == '\t'))
      cursor++;
    return cursor - start;
  }

  /** 補助エージェントへの委譲で用いる非公開化値を返す。 */
  private static String maskValue(String content) {
    var auth = AUTH_VALUE_PREFIX.matcher(content);
    return auth.lookingAt()
        ? content.substring(0, auth.end()) + maskKeepingNewlines(content.substring(auth.end()))
        : maskKeepingNewlines(content);
  }

  /** 補助エージェントへの委譲で用いる非公開化KeepingNewlinesを返す。 */
  private static String maskKeepingNewlines(String content) {
    var result = new StringBuilder(MARKER);
    for (int index = 0; index < content.length(); index++) {
      char next = content.charAt(index);
      if (next == '\r' || next == '\n') result.append(next);
    }
    return result.toString();
  }
}
