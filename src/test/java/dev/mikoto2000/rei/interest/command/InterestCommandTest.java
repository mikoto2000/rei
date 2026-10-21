package dev.mikoto2000.rei.interest.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.interest.InterestDiscoveryJob;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import picocli.CommandLine;

/** 関心コマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class InterestCommandTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listPrintsRecentInterestUpdates() {
    InterestUpdateService service =
        new InterestUpdateService(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("interest-command.db")));
    service.save(
        "Neovim 開発環境",
        "繰り返し話題になっている",
        "Neovim devcontainer best practices",
        "Neovim docs",
        List.of("https://example.com/nvim"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("list");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("Neovim 開発環境"));
    assertTrue(output.contains("Neovim docs"));
    assertTrue(output.contains("https://example.com/nvim"));
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listPrintsEmptyMessageWhenNoUpdatesExist() {
    InterestUpdateService service =
        new InterestUpdateService(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("interest-command-empty.db")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service).execute("list", "--hours", "1");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("興味更新はありません"));
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void discoverRunsInterestDiscoveryJobAndPrintsSavedTopics() {
    InterestUpdateService service =
        new InterestUpdateService(
            new DriverManagerDataSource(
                "jdbc:sqlite:" + tempDir.resolve("interest-command-discover.db")));
    InterestDiscoveryJob job = org.mockito.Mockito.mock(InterestDiscoveryJob.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              Consumer<String> progress = invocation.getArgument(0);
              progress.accept("候補トピックを抽出しています...");
              progress.accept("1/2 件目を検索しています: Neovim 開発環境");
              return List.of(
                  new dev.mikoto2000.rei.interest.InterestUpdate(
                      1L,
                      "Neovim 開発環境",
                      "繰り返し話題になっている",
                      "Neovim devcontainer best practices",
                      "Neovim docs",
                      List.of("https://example.com/nvim"),
                      OffsetDateTime.of(2026, 4, 18, 0, 0, 0, 0, ZoneOffset.UTC)),
                  new dev.mikoto2000.rei.interest.InterestUpdate(
                      2L,
                      "GitHub Actions 最適化",
                      "CI の話題が繰り返し出ている",
                      "GitHub Actions caching best practices",
                      "GitHub Actions docs",
                      List.of("https://example.com/actions"),
                      OffsetDateTime.of(2026, 4, 18, 0, 5, 0, 0, ZoneOffset.UTC)));
            })
        .when(job)
        .discoverNow(org.mockito.ArgumentMatchers.any());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = newCommand(service, job).execute("discover");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("候補トピックを抽出しています..."));
    assertTrue(output.contains("1/2 件目を検索しています: Neovim 開発環境"));
    assertTrue(output.contains("興味更新を 2 件追加しました"));
    assertTrue(output.contains("Neovim 開発環境"));
    assertTrue(output.contains("GitHub Actions 最適化"));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(InterestUpdateService service) {
    return newCommand(service, org.mockito.Mockito.mock(InterestDiscoveryJob.class));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(InterestUpdateService service, InterestDiscoveryJob job) {
    return new CommandLine(
        new InterestCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls == InterestCommand.ListCommand.class) {
              return cls.cast(new InterestCommand.ListCommand(service));
            }
            if (cls == InterestCommand.DiscoverCommand.class) {
              return cls.cast(new InterestCommand.DiscoverCommand(job));
            }
            return CommandLine.defaultFactory().create(cls);
          }
        });
  }
}
