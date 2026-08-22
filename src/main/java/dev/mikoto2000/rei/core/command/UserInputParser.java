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
    List<String> words = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inSingleQuote = false;
    boolean inDoubleQuote = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'' && !inDoubleQuote) {
        inSingleQuote = !inSingleQuote;
      } else if (c == '"' && !inSingleQuote) {
        inDoubleQuote = !inDoubleQuote;
      } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
        if (!current.isEmpty()) {
          words.add(current.toString());
          current.setLength(0);
        }
      } else {
        current.append(c);
      }
    }
    if (!current.isEmpty()) {
      words.add(current.toString());
    }
    return words.toArray(String[]::new);
  }
}
