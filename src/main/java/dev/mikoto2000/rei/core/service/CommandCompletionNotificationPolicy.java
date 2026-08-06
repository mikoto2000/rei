package dev.mikoto2000.rei.core.service;

import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class CommandCompletionNotificationPolicy {

  private static final Set<String> DISABLED_ROOT_COMMANDS = Set.of(
      "model",
      "models",
      "project");

  public boolean shouldNotify(String... args) {
    if (args == null || args.length == 0 || args[0] == null) {
      return true;
    }
    return !DISABLED_ROOT_COMMANDS.contains(args[0]);
  }
}
