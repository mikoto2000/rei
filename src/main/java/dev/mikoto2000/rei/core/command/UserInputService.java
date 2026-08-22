package dev.mikoto2000.rei.core.command;

/** Canonical, UI-independent interpretation of input as established by the Shell. */
public final class UserInputService {

  public enum Kind { EMPTY, CHAT, EXIT, HELP, VERSION, PASTE, COMMAND }

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
      Kind special = switch (arguments[0]) {
        case "exit", "quit" -> Kind.EXIT;
        case "help" -> Kind.HELP;
        case "version" -> Kind.VERSION;
        case "paste" -> Kind.PASTE;
        default -> Kind.COMMAND;
      };
      return input(special, parsed.text(), arguments);
    }
    return input(Kind.COMMAND, parsed.text(), arguments);
  }

  private Input input(Kind kind, String text, String[] arguments) {
    return new Input(kind, text, arguments);
  }
}
