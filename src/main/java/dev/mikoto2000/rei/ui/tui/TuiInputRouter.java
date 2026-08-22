package dev.mikoto2000.rei.ui.tui;

import java.util.Set;

import dev.mikoto2000.rei.core.command.UserInputParser;

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

  private static final Set<String> SAFE_WHILE_RUNNING = Set.of("help", "version");
  private static final Set<String> SHELL_ONLY = Set.of("sh", "paste");
  private final UserInputParser parser;

  TuiInputRouter(UserInputParser parser) {
    this.parser = parser;
  }

  Route route(String input, boolean running) {
    UserInputParser.ParsedInput parsed = parser.parse(input);
    if (parsed.kind() == UserInputParser.Kind.EMPTY) {
      return route(Kind.EMPTY, "", new String[0], "");
    }
    if (parsed.kind() == UserInputParser.Kind.CHAT) {
      return running
          ? route(Kind.MESSAGE, "", new String[0], "Agent is already running.")
          : route(Kind.CHAT, parsed.text(), new String[0], "");
    }

    String[] arguments = parsed.arguments();
    if (arguments.length == 0) {
      return route(Kind.EMPTY, "", arguments, "");
    }
    String command = arguments[0];
    if ("exit".equals(command) || "quit".equals(command)) {
      return route(Kind.EXIT, "", arguments, "");
    }
    if ("tui".equals(command)) {
      return route(Kind.MESSAGE, "", arguments, "Already in TUI mode.");
    }
    if (SHELL_ONLY.contains(command)) {
      return route(Kind.MESSAGE, "", arguments, "This command is not available in TUI mode.");
    }
    if (running && !SAFE_WHILE_RUNNING.contains(command)) {
      return route(Kind.MESSAGE, "", arguments, "Command is not available while the agent is running.");
    }
    return route(Kind.COMMAND, "", arguments, "");
  }

  private Route route(Kind kind, String text, String[] arguments, String message) {
    return new Route(kind, text, arguments, message);
  }
}
