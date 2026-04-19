package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import dev.mikoto2000.rei.config.ExternalConfigFileService;

class ConfigCommandTest {

  @TempDir
  Path tempDir;

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

  private CommandLine newCommand(ExternalConfigFileService service) {
    return new CommandLine(new ConfigCommand(), new CommandLine.IFactory() {
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
