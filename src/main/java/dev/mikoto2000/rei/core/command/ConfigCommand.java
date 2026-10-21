package dev.mikoto2000.rei.core.command;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** 外部設定ファイルを操作しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "config",
    description = "外部設定ファイルを操作します",
    subcommands = {ConfigCommand.PathCommand.class, ConfigCommand.InitCommand.class})
public class ConfigCommand {

  /** 外部設定ファイルのパスを表示しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "path", description = "外部設定ファイルのパスを表示します")
  public static class PathCommand implements Runnable {

    /** 外部設定ファイルサービス。 */
    private final ExternalConfigFileService externalConfigFileService;

    /** 外部設定ファイルのパスを表示しますを行う。 */
    @Override
    public void run() {
      System.out.println(externalConfigFileService.configFilePath());
    }
  }

  /** 外部設定ファイルのテンプレートを作成しますを行うための利用者コマンド。 */
  @Component
  @RequiredArgsConstructor
  @Command(name = "init", description = "外部設定ファイルのテンプレートを作成します")
  public static class InitCommand implements Runnable {

    /** 外部設定ファイルサービス。 */
    private final ExternalConfigFileService externalConfigFileService;

    /** 強制。 */
    @Option(names = "--force", description = "既存ファイルを上書きします")
    boolean force;

    /** 外部設定ファイルのテンプレートを作成しますを行う。 */
    @Override
    public void run() {
      var path = externalConfigFileService.initializeConfigFile(force);
      System.out.println("設定テンプレートを作成しました: " + path);
    }
  }
}
