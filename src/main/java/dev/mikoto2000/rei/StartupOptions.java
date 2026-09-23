package dev.mikoto2000.rei;

import java.nio.file.Path;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;

/** Parsed before Spring starts; unrelated arguments remain available to Spring. */
@CommandLine.Command(name = "rei", description = "AI shell", mixinStandardHelpOptions = true, version = "v1.0.0")
public final class StartupOptions {
  @CommandLine.Option(names = {"-p", "--project"}, paramLabel = "<directory>",
      description = "起動時のカレントプロジェクトのディレクトリを指定します")
  private String directory;
  private Path project;
  private boolean helpRequested;
  private boolean versionRequested;

  public static StartupOptions parse(String[] args, Path startupDirectory) {
    var options = new StartupOptions();
    var command = new CommandLine(options).setUnmatchedArgumentsAllowed(true);
    var parsed = command.parseArgs(args);
    options.helpRequested = parsed.isUsageHelpRequested();
    options.versionRequested = parsed.isVersionHelpRequested();
    if (options.directory != null && !options.helpRequested && !options.versionRequested) {
      try {
        options.project = ProjectService.resolveExistingDirectory(startupDirectory, options.directory);
      } catch (IllegalArgumentException error) {
        throw new CommandLine.ParameterException(command, "Invalid --project '" + options.directory + "': "
            + error.getMessage(), error);
      }
    }
    return options;
  }

  public Path project() { return project; }
  public boolean helpRequested() { return helpRequested; }
  public boolean printHelpIfRequested() {
    var command = new CommandLine(this);
    if (helpRequested) command.usage(System.out);
    else if (versionRequested) command.printVersionHelp(System.out);
    return helpRequested || versionRequested;
  }
}
