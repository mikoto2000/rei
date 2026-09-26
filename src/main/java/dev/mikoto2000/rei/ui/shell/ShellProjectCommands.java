package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;

/** Shell-only binding and restoration, including commands dispatched to a worker thread. */
public final class ShellProjectCommands {
  private ShellProjectCommands() {}

  public static void configure(CommandLine command, ProjectService projects,
      ProjectClient client, ProjectShellActivity activity) {
    var delegate = command.getExecutionStrategy();
    command.setExecutionStrategy(parsed -> {
      try (var scope = client.open()) {
        int result = delegate.execute(parsed);
        if (activity != null) activity.refreshSession();
        var leaf = parsed;
        while (leaf.hasSubcommand()) leaf = leaf.subcommand();
        if (result == 0 && !parsed.isUsageHelpRequested() && !parsed.isVersionHelpRequested()
            && !leaf.isUsageHelpRequested() && !leaf.isVersionHelpRequested()
            && leaf.commandSpec().userObject() instanceof ProjectCommand.CdCommand && activity != null)
          activity.restore(projects.currentContext());
        return result;
      }
    });
  }
}
