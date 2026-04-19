package dev.mikoto2000.rei.core.command;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Component
@Command(
    name = "config",
    description = "外部設定ファイルを操作します",
    subcommands = {
      ConfigCommand.PathCommand.class,
      ConfigCommand.InitCommand.class
    })
public class ConfigCommand {

  @Component
  @RequiredArgsConstructor
  @Command(name = "path", description = "外部設定ファイルのパスを表示します")
  public static class PathCommand implements Runnable {

    private final ExternalConfigFileService externalConfigFileService;

    @Override
    public void run() {
      System.out.println(externalConfigFileService.configFilePath());
    }
  }

  @Component
  @RequiredArgsConstructor
  @Command(name = "init", description = "外部設定ファイルのテンプレートを作成します")
  public static class InitCommand implements Runnable {

    private final ExternalConfigFileService externalConfigFileService;

    @Option(names = "--force", description = "既存ファイルを上書きします")
    boolean force;

    @Override
    public void run() {
      var path = externalConfigFileService.initializeConfigFile(force);
      System.out.println("設定テンプレートを作成しました: " + path);
    }
  }
}
