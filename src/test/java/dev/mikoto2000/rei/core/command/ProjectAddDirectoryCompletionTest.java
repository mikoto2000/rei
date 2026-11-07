package dev.mikoto2000.rei.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.ui.shell.JLineCompletionAdapter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/** プロジェクト追加ディレクトリ完了の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectAddDirectoryCompletionTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesAbsoluteDirectoryFragment() throws Exception {
    Path target = tempDir.resolve("project-alpha");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("other"));

    String fragment = tempDir.resolve("proj").toString();

    assertThat(completedDirectories("/project add " + fragment))
        .containsExactly(target);
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completesRelativeDirectoryFragmentFromCurrentProject() throws Exception {
    Path target = tempDir.resolve("src-main");
    java.nio.file.Files.createDirectory(target);
    java.nio.file.Files.createDirectory(tempDir.resolve("build"));

    assertThat(completedDirectories("/project add src"))
        .containsExactly(target);
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void quotesCandidateWhenInputIsQuoted() throws Exception {
    Path target = tempDir.resolve("project space");
    java.nio.file.Files.createDirectory(target);

    String fragment = tempDir.resolve("project").toString();

    assertThat(completedDirectories("/project add \"" + fragment))
        .containsExactly(target);
  }

  private List<Path> completedDirectories(String line) {
    var command = new CommandLine(CommandSpec.create()).addSubcommand("project", new ProjectCommand());
    var parser = ReiLineReaderFactory.parser();
    var parsed = (CompletingParsedLine) parser.parse(line, line.length(), Parser.ParseContext.COMPLETE);
    var candidates = new ArrayList<Candidate>();
    new JLineCompletionAdapter(command, ReiLineReaderFactory.completionEngine(), () -> tempDir)
        .complete(null, parsed, candidates);
    return candidates.stream()
        .map(candidate -> parsed.escape(candidate.value(), true).toString())
        .map(argument -> new UserInputParser().split("/project add " + argument)[2])
        .map(path -> tempDir.resolve(path).normalize())
        .toList();
  }
}
