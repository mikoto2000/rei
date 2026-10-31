package dev.mikoto2000.rei.ui.shell;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.command.ProjectCommand;
import dev.mikoto2000.rei.core.command.ReiLineReaderFactory;
import dev.mikoto2000.rei.core.command.UserInputParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** J行完了入力の結果と状態の契約を検証するテスト。 */
@Timeout(15)
@org.junit.jupiter.api.Tag("integration")
class JLineCompletionInputTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code tab} の条件を再現するための補助情報を返す。 */
  String tab(String input) throws Exception {
    return read(input + "\t");
  }

  /** 検証用に、指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  String read(String input) throws Exception {
    var command =
        new CommandLine(CommandSpec.create())
            .addSubcommand("project", new ProjectCommand())
            .addSubcommand("secretary", new dev.mikoto2000.rei.secretary.SecretaryCommand())
            .addSubcommand("approval", new dev.mikoto2000.rei.core.policy.ApprovalCommand());
    try (var terminal =
        new org.jline.terminal.impl.DumbTerminal(
            "fixture",
            "xterm",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            java.nio.charset.StandardCharsets.UTF_8)) {
      terminal.setSize(new Size(100, 30));
      var reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .parser(ReiLineReaderFactory.parser())
              .completer(
                  new JLineCompletionAdapter(
                      command, ReiLineReaderFactory.completionEngine(), () -> root))
              .build();
      reader.runMacro(input + "\n");
      return reader.readLine("");
    }
  }

  /** 実際のTab入力で秘書操作を確定し、次の引数へ進めることを確認する。 */
  @Test
  void tabCompletesSecretaryAction() throws Exception {
    assertThat(tab("/secretary life-con")).isEqualTo("/secretary life-configure ");
    assertThat(tab("/secretary --project project-id work-ver"))
        .isEqualTo("/secretary --project project-id work-verify ");
    assertThat(tab("/secretary show task-id ")).isEqualTo("/secretary show task-id ");
  }

  /** ターミナルでの対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void tabCompletesApprovalAction() throws Exception {
    assertThat(tab("/approval ap")).isEqualTo("/approval approve ");
  }

  /** ターミナルでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "f:\\project\\rei",
        "F:\\",
        "..\\rei\\",
        "\\\\server\\share\\rei",
        "\"F:\\My Documents\\rei\\\"",
        "'F:\\My Documents\\rei\\'"
      })
  void typedWindowsPathPreservesSingleBackslashes(String path) throws Exception {
    assertThat(read("/project cd " + path)).isEqualTo("/project cd " + path);
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void tabCompletesWindowsPathAndPassesItToCd() throws Exception {
    Path directory = Files.createDirectories(root.resolve("My Documents/child"));
    String completed = tab("/project cd " + root + "\\My\tch");
    assertThat(completed).contains(directory.toString() + "\\");
    var service = org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var command =
        new CommandLine(CommandSpec.create())
            .addSubcommand(
                "project",
                new CommandLine(CommandSpec.create())
                    .addSubcommand("cd", new ProjectCommand.CdCommand(service)));
    assertThat(command.execute(new UserInputParser().parse(completed).arguments())).isZero();
    org.mockito.Mockito.verify(service).cd(directory.toString() + "\\");
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void tabCompletesCommandAndAppendsSpace() throws Exception {
    assertThat(tab("/pro")).isEqualTo("/project ");
  }

  /** ディレクトリ開始を維持することを確認する。 */
  @Test
  void tabQuotesSpaceAndKeepsDirectoryOpenForNextLevel() throws Exception {
    Files.createDirectory(root.resolve("My Documents"));
    var result = tab("/project add ./My");
    assertThat(new UserInputParser().split(result)).endsWith("./My Documents/");
    assertThat(result).doesNotEndWith(" ");
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void tabWithinExistingQuoteRoundTrips() throws Exception {
    Files.createDirectory(root.resolve("My Documents"));
    for (String quote : new String[] {"\"", "'"})
      assertThat(new UserInputParser().split(tab("/project add " + quote + "./My")))
          .endsWith("./My Documents/");
  }

  /** ターミナルでの対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void tabInMiddlePreservesFollowingArgument() throws Exception {
    assertThat(tab("/project c foo\u0002\u0002\u0002\u0002")).isEqualTo("/project cd foo");
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void repeatedTabDescendsThroughQuotedDirectory() throws Exception {
    Files.createDirectories(root.resolve("My Documents/child"));
    assertThat(new UserInputParser().split(tab("/project add ./My\tch")))
        .endsWith("./My Documents/child/");
  }

  /** ターミナルでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void homeCandidateCanActuallyBeInsertedByJline() throws Exception {
    // An injected home keeps this test independent of the developer's real files.
    Files.createDirectory(root.resolve("reports"));
    var command =
        new CommandLine(CommandSpec.create())
            .addSubcommand("project", new ProjectCommand())
            .addSubcommand("secretary", new dev.mikoto2000.rei.secretary.SecretaryCommand())
            .addSubcommand("approval", new dev.mikoto2000.rei.core.policy.ApprovalCommand());
    try (var terminal =
        new org.jline.terminal.impl.DumbTerminal(
            "fixture",
            "xterm",
            new ByteArrayInputStream(new byte[0]),
            new ByteArrayOutputStream(),
            java.nio.charset.StandardCharsets.UTF_8)) {
      terminal.setSize(new Size(100, 30));
      var reader =
          LineReaderBuilder.builder()
              .terminal(terminal)
              .parser(new ShellCompletionParser(root))
              .completer(
                  new JLineCompletionAdapter(
                      command, ReiLineReaderFactory.completionEngine(), () -> root))
              .build();
      reader.runMacro("/project add ~/rep\t\n");
      assertThat(new UserInputParser().split(reader.readLine("")))
          .endsWith(root.resolve("reports").toString().replace('\\', '/') + "/");
    }
  }
}
