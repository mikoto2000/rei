package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.config.ExternalConfigFileService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** 設定コマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ConfigCommandTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void pathPrintsExternalConfigFilePath() {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("path");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains(service.configFilePath().toString()));
  }

  /** 初期化について、雛形を作成することを確認する。 */
  @Test
  void initCreatesTemplateAndPrintsPath() {
    ExternalConfigFileService service = new ExternalConfigFileService(tempDir);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("init");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("設定テンプレートを作成しました"));
    assertTrue(out.toString().contains(service.configFilePath().toString()));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(ExternalConfigFileService service) {
    return new CommandLine(
        new ConfigCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls == ConfigCommand.PathCommand.class) {
              return cls.cast(new ConfigCommand.PathCommand(service));
            }
            if (cls == ConfigCommand.InitCommand.class) {
              return cls.cast(new ConfigCommand.InitCommand(service));
            }
            return CommandLine.defaultFactory().create(cls);
          }
        });
  }
}
