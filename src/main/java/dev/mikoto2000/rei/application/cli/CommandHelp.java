package dev.mikoto2000.rei.application.cli;

import java.util.ArrayList;
import java.util.HashSet;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/** コマンド階層全体に、副作用のない標準ヘルプを設定する。 */
public final class CommandHelp {
  private CommandHelp() {}

  /** 既存の短縮名やバージョン設定を保持して --help を有効にする。 */
  public static CommandLine install(CommandLine line) {
    var spec = line.getCommandSpec();
    if (!spec.optionsMap().containsKey("--help")) {
      spec.addOption(
          OptionSpec.builder("--help")
              .type(boolean.class)
              .arity("0")
              .usageHelp(true)
              .description("このコマンドの役割と使い方を表示します。")
              .build());
    }
    for (var option : new ArrayList<>(spec.options())) {
      var names = java.util.List.of(option.names());
      if (names.contains("--help") || names.contains("--version")) {
        spec.remove(option);
        spec.addOption(
            option.toBuilder()
                .usageHelp(names.contains("--help"))
                .versionHelp(names.contains("--version"))
                .build());
      }
    }
    new HashSet<>(line.getSubcommands().values()).forEach(CommandHelp::install);
    return line;
  }
}
