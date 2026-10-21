package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.completion.PathFragment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;

/**
 * シェル入力補完の入力を解釈し、機能内で扱える情報へ整える。
 *
 * <p>JLine's standard completion contract backed by the exact execution tokenizer.
 */
public final class ShellCompletionParser implements Parser {
  /** 利用者のホームディレクトリ。 */
  private final Path home;

  /** 語句の分割。 */
  private final UserInputParser tokenizer = new UserInputParser();

  /** 指定された値や利用先を使って、ShellCompletionParserを初期化する。 */
  public ShellCompletionParser(Path home) {
    this.home = home;
  }

  // LineReader.finish() also consults this flag after parsing. The default treats
  // backslashes as escapes and strips Windows path separators before execution.
  /** ターミナルでの対話において、特殊文字の保護文字かどうかを判定する。 */
  @Override
  public boolean isEscapeChar(char ch) {
    return false;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  @Override
  public ParsedLine parse(String line, int cursor, ParseContext context) {
    if (cursor < 0 || cursor > line.length())
      throw new IllegalArgumentException("Cursor outside input");
    var tokens = tokenizer.tokenize(line);
    var words = new ArrayList<String>();
    tokens.forEach(token -> words.add(token.value()));
    int index = 0;
    while (index < tokens.size() && tokens.get(index).end() < cursor) index++;
    int start = cursor, end = cursor;
    String word = "", prefix = "";
    if (index < tokens.size() && tokens.get(index).start() <= cursor) {
      var token = tokens.get(index);
      start = token.start();
      end = token.end();
      word = token.value();
      var beforeCursor = tokenizer.tokenize(line.substring(start, cursor));
      prefix = beforeCursor.isEmpty() ? "" : beforeCursor.getFirst().value();
    } else words.add(index, "");
    char quote =
        start < line.length() && (line.charAt(start) == '\'' || line.charAt(start) == '"')
            ? line.charAt(start)
            : 0;
    if (context == ParseContext.COMPLETE && !prefix.isEmpty()) {
      boolean windows = java.io.File.separatorChar == '\\';
      word = PathFragment.expandHome(word, home, windows);
      prefix = PathFragment.expandHome(prefix, home, windows);
    }
    return new CompletionLine(
        line, cursor, List.copyOf(words), index, word, prefix.length(), start, end, quote);
  }

  /**
   * ターミナルでの対話で受け渡す入力補完行を表す。
   *
   * @param line 行
   * @param cursor 続きの取得位置
   * @param words 語句一覧
   * @param wordIndex 語句位置
   * @param word 語句
   * @param wordCursor 語句続きの取得位置
   * @param start 開始
   * @param end 終了
   * @param quote 引用
   */
  private record CompletionLine(
      String line,
      int cursor,
      List<String> words,
      int wordIndex,
      String word,
      int wordCursor,
      int start,
      int end,
      char quote)
      implements CompletingParsedLine {
    /** ターミナルでの対話で用いる元の語句続きの取得位置を返す。 */
    public int rawWordCursor() {
      return cursor - start;
    }

    /** ターミナルでの対話で用いる元の語句長さを返す。 */
    public int rawWordLength() {
      return end - start;
    }

    /** ターミナルでの対話で用いる特殊文字の保護を返す。 */
    public CharSequence escape(CharSequence candidate, boolean complete) {
      return UserInputParser.quote(candidate.toString(), complete, quote);
    }
  }
}
