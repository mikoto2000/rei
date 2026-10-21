package dev.mikoto2000.rei.core.command;

import java.util.ArrayList;
import java.util.List;

/**
 * 利用者入力の入力を解釈し、機能内で扱える情報へ整える。
 *
 * <p>UI-independent classification and tokenization of shell-style user input.
 */
public final class UserInputParser {

  /** 共通の実行基盤における種類の選択肢。 */
  public enum Kind {
    /** 空。 */
    EMPTY,
    /** チャット。 */
    CHAT,
    /** SLASHコマンド。 */
    SLASH_COMMAND
  }

  /**
   * 共通の実行基盤で受け渡す解釈済み入力を表す。
   *
   * @param kind 種類
   * @param text テキスト
   * @param arguments 引数
   */
  public record ParsedInput(Kind kind, String text, String[] arguments) {
    /** 受け取った情報からParsedInputを作成する。 */
    public ParsedInput {
      arguments = arguments.clone();
    }

    /** 共通の実行基盤で用いる引数を返す。 */
    @Override
    public String[] arguments() {
      return arguments.clone();
    }
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public ParsedInput parse(String input) {
    String text = input == null ? "" : input.trim();
    if (text.isEmpty()) {
      return new ParsedInput(Kind.EMPTY, "", new String[0]);
    }
    if (!text.startsWith("/")) {
      return new ParsedInput(Kind.CHAT, input, new String[0]);
    }
    return new ParsedInput(Kind.SLASH_COMMAND, text, split(text.substring(1).trim()));
  }

  /** 共通の実行基盤で用いる分割を返す。 */
  public String[] split(String line) {
    // Preserve execution semantics: explicitly empty quoted arguments were historically omitted.
    return tokenize(line).stream()
        .map(Token::value)
        .filter(value -> !value.isEmpty())
        .toArray(String[]::new);
  }

  /**
   * 共通の実行基盤で受け渡すトークンを表す。
   *
   * <p>Shared lexical rules, with raw spans for cursor-aware input adapters.
   *
   * @param value 値
   * @param start 開始
   * @param end 終了
   */
  public record Token(String value, int start, int end) {}

  /** 共通の実行基盤で用いるtokenizeを返す。 */
  public List<Token> tokenize(String line) {
    List<Token> words = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean single = false, doubled = false;
    int start = -1;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (Character.isWhitespace(c) && !single && !doubled) {
        if (start >= 0) {
          words.add(new Token(current.toString(), start, i));
          current.setLength(0);
          start = -1;
        }
        continue;
      }
      if (start < 0) start = i;
      if (c == '\'' && !doubled) single = !single;
      else if (c == '"' && !single) doubled = !doubled;
      else current.append(c);
    }
    if (start >= 0) words.add(new Token(current.toString(), start, line.length()));
    return List.copyOf(words);
  }

  /**
   * 共通の実行基盤で用いる引用を返す。
   *
   * <p>Encode one argument using the same literal-backslash grammar as split().
   */
  public static String quote(String value, boolean complete, char preferredQuote) {
    boolean needsQuotes =
        preferredQuote != 0
            || value.chars().anyMatch(c -> Character.isWhitespace(c) || c == '\'' || c == '"');
    if (!needsQuotes) return value;
    char quote = preferredQuote == 0 ? '"' : preferredQuote;
    char other = quote == '"' ? '\'' : '"';
    var result = new StringBuilder().append(quote);
    for (char c : value.toCharArray()) {
      if (c == quote) result.append(quote).append(other).append(c).append(other).append(quote);
      else result.append(c);
    }
    if (complete) result.append(quote);
    return result.toString();
  }
}
