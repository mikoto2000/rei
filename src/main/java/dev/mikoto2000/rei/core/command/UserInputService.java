package dev.mikoto2000.rei.core.command;

/** Canonical, UI-independent interpretation of input as established by the Shell. */
public final class UserInputService {

  public enum Kind { EMPTY, CHAT, EXIT, HELP, VERSION, PASTE, COMMAND }

  private static final java.util.Map<String, Kind> BUILTINS = java.util.Map.of(
      "exit", Kind.EXIT, "quit", Kind.EXIT, "help", Kind.HELP, "version", Kind.VERSION, "paste", Kind.PASTE);
  public static java.util.Map<String, Kind> builtins() { return BUILTINS; }

  public record Input(Kind kind, String text, String[] arguments) {
    public Input {
      arguments = arguments.clone();
    }

    @Override
    public String[] arguments() {
      return arguments.clone();
    }
  }

  private final UserInputParser parser;

  public UserInputService(UserInputParser parser) {
    this.parser = parser;
  }

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

  private Input input(Kind kind, String text, String[] arguments) {
    return new Input(kind, text, arguments);
  }
}
