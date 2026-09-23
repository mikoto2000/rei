package dev.mikoto2000.rei.ui.shell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.*;
import dev.mikoto2000.rei.core.command.UserInputParser;
import dev.mikoto2000.rei.core.completion.PathFragment;

/** JLine's standard completion contract backed by the exact execution tokenizer. */
public final class ShellCompletionParser implements Parser {
  private final Path home;
  private final UserInputParser tokenizer = new UserInputParser();
  public ShellCompletionParser(Path home) { this.home = home; }

  // LineReader.finish() also consults this flag after parsing. The default treats
  // backslashes as escapes and strips Windows path separators before execution.
  @Override public boolean isEscapeChar(char ch) { return false; }

  @Override public ParsedLine parse(String line, int cursor, ParseContext context) {
    if (cursor < 0 || cursor > line.length()) throw new IllegalArgumentException("Cursor outside input");
    var tokens = tokenizer.tokenize(line);
    var words = new ArrayList<String>();
    tokens.forEach(token -> words.add(token.value()));
    int index = 0;
    while (index < tokens.size() && tokens.get(index).end() < cursor) index++;
    int start = cursor, end = cursor;
    String word = "", prefix = "";
    if (index < tokens.size() && tokens.get(index).start() <= cursor) {
      var token = tokens.get(index);
      start = token.start(); end = token.end(); word = token.value();
      var beforeCursor = tokenizer.tokenize(line.substring(start, cursor));
      prefix = beforeCursor.isEmpty() ? "" : beforeCursor.getFirst().value();
    } else words.add(index, "");
    char quote = start < line.length() && (line.charAt(start) == '\'' || line.charAt(start) == '"')
        ? line.charAt(start) : 0;
    if (context == ParseContext.COMPLETE && !prefix.isEmpty()) {
      boolean windows = java.io.File.separatorChar == '\\';
      word = PathFragment.expandHome(word, home, windows);
      prefix = PathFragment.expandHome(prefix, home, windows);
    }
    return new CompletionLine(line, cursor, List.copyOf(words), index, word, prefix.length(), start, end, quote);
  }

  private record CompletionLine(String line, int cursor, List<String> words, int wordIndex,
      String word, int wordCursor, int start, int end, char quote) implements CompletingParsedLine {
    public int rawWordCursor() { return cursor - start; }
    public int rawWordLength() { return end - start; }
    public CharSequence escape(CharSequence candidate, boolean complete) {
      return UserInputParser.quote(candidate.toString(), complete, quote);
    }
  }
}
