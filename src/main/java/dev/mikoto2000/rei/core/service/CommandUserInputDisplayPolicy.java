package dev.mikoto2000.rei.core.service;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class CommandUserInputDisplayPolicy {

  private static final Set<String> HIDDEN_ROOT_COMMANDS = Set.of("project", "sh");

  public boolean shouldDisplay(String... args) {
    if (args == null || args.length == 0 || args[0] == null) {
      return true;
    }
    return !HIDDEN_ROOT_COMMANDS.contains(args[0]);
  }
}
