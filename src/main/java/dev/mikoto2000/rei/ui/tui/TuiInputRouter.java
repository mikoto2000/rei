package dev.mikoto2000.rei.ui.tui;

import java.util.Set;

import dev.mikoto2000.rei.core.command.UserInputService;

/** Pure routing policy for input submitted from the TUI. */
final class TuiInputRouter {

  enum Kind { EMPTY, CHAT, COMMAND, EXIT, MESSAGE }

  record Route(Kind kind, String text, String[] arguments, String message) {
    Route {
      arguments = arguments.clone();
    }

    @Override
    public String[] arguments() {
      return arguments.clone();
    }
  }

  private static final Set<String> SHELL_ONLY = Set.of("sh");
  private final UserInputService inputs;

  TuiInputRouter(UserInputService inputs) {
    this.inputs = inputs;
  }

  Route route(String input, boolean running) {
    UserInputService.Input interpreted = inputs.interpret(input);
    switch (interpreted.kind()) {
      case EMPTY:
        return route(Kind.EMPTY, "", new String[0], "");
      case CHAT:
        return running
            ? route(Kind.MESSAGE, "", new String[0], "Agent is already running.")
            : route(Kind.CHAT, interpreted.text(), new String[0], "");
      case EXIT:
        return route(Kind.EXIT, "", interpreted.arguments(), "");
      case HELP:
        return route(Kind.COMMAND, "", interpreted.arguments(), "");
      case VERSION:
        return route(Kind.COMMAND, "", interpreted.arguments(), "");
      case PASTE:
        return route(Kind.MESSAGE, "", interpreted.arguments(),
            "This command is not available in TUI mode.");
      case COMMAND:
        break;
    }

    String[] arguments = interpreted.arguments();
    String command = arguments[0];
    if ("tui".equals(command)) {
      return route(Kind.MESSAGE, "", arguments, "Already in TUI mode.");
    }
    if (SHELL_ONLY.contains(command)) {
      return route(Kind.MESSAGE, "", arguments, "This command is not available in TUI mode.");
    }
    if (running) {
      return route(Kind.MESSAGE, "", arguments, "Command is not available while the agent is running.");
    }
    return route(Kind.COMMAND, "", arguments, "");
  }

  private Route route(Kind kind, String text, String[] arguments, String message) {
    return new Route(kind, text, arguments, message);
  }
}
