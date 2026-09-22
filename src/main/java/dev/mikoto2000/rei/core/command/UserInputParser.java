package dev.mikoto2000.rei.core.command;

import java.util.ArrayList;
import java.util.List;

/** UI-independent classification and tokenization of shell-style user input. */
public final class UserInputParser {

  public enum Kind { EMPTY, CHAT, SLASH_COMMAND }

  public record ParsedInput(Kind kind, String text, String[] arguments) {
    public ParsedInput {
      arguments = arguments.clone();
    }

    @Override
    public String[] arguments() {
      return arguments.clone();
    }
  }

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

  public String[] split(String line) {
    // Preserve execution semantics: explicitly empty quoted arguments were historically omitted.
    return tokenize(line).stream().map(Token::value).filter(value -> !value.isEmpty()).toArray(String[]::new);
  }

  /** Shared lexical rules, with raw spans for cursor-aware input adapters. */
  public record Token(String value, int start, int end) { }

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

  /** Encode one argument using the same literal-backslash grammar as split(). */
  public static String quote(String value, boolean complete, char preferredQuote) {
    boolean needsQuotes = preferredQuote != 0 || value.chars()
        .anyMatch(c -> Character.isWhitespace(c) || c == '\'' || c == '"');
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
