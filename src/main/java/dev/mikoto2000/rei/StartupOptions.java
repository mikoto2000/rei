package dev.mikoto2000.rei;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import picocli.CommandLine;

/**
 * AI シェルを行うための利用者コマンド。
 *
 * <p>Parsed before Spring starts; unrelated arguments remain available to Spring.
 */
@CommandLine.Command(
    name = "rei",
    description = "AI シェル",
    mixinStandardHelpOptions = true,
    versionProvider = ReiVersionProvider.class)
public final class StartupOptions {
  /** 動作モード。 */
  @CommandLine.Option(
      names = "--mode",
      paramLabel = "<server|legacy-shell>",
      description = "起動方式を指定します（軽量 CLI への移行中は既定値 legacy-shell）")
  private String mode;

  /** ディレクトリ。 */
  @CommandLine.Option(
      names = {"-p", "--project"},
      paramLabel = "<directory>",
      description = "起動時のカレントプロジェクトのディレクトリを指定します")
  private String directory;

  /** バックエンド。 */
  @CommandLine.Option(names = "--backend", description = "Shell を開かず Backend を常駐起動します")
  private boolean backend;

  /** プロジェクト。 */
  private Path project;

  /** 使い方要求された。 */
  private boolean helpRequested;

  /** 形式のバージョン要求された。 */
  private boolean versionRequested;

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static StartupOptions parse(String[] args, Path startupDirectory) {
    var options = new StartupOptions();
    var command = new CommandLine(options).setUnmatchedArgumentsAllowed(true);
    var parsed = command.parseArgs(args);
    options.helpRequested = parsed.isUsageHelpRequested();
    options.versionRequested = parsed.isVersionHelpRequested();
    if (options.directory != null && !options.helpRequested && !options.versionRequested) {
      try {
        options.project =
            ProjectService.resolveExistingDirectory(startupDirectory, options.directory);
      } catch (IllegalArgumentException error) {
        throw new CommandLine.ParameterException(
            command, "Invalid --project '" + options.directory + "': " + error.getMessage(), error);
      }
    }
    return options;
  }

  /** れいの起動で用いるプロジェクトを返す。 */
  public Path project() {
    return project;
  }

  /** れいの起動において、バックエンドに該当するか判定する。 */
  public boolean backend() {
    return backend || "server".equals(mode);
  }

  /** れいの起動において、使い方要求されたに該当するか判定する。 */
  public boolean helpRequested() {
    return helpRequested;
  }

  /** れいの起動において、出力使い方条件付き要求されたに該当するか判定する。 */
  public boolean printHelpIfRequested() {
    var command = new CommandLine(this);
    if (helpRequested) command.usage(System.out);
    else if (versionRequested) command.printVersionHelp(System.out);
    return helpRequested || versionRequested;
  }
}
