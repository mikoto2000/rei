package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** プロジェクトコマンドの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectCommandTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** プロジェクトを取り除くことを確認する。 */
  @Test
  void projectCommandAddsListsChangesAndRemovesProject() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service =
        connect(new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects")));
    CommandLine commandLine = newCommand(service);

    assertEquals(0, commandLine.execute("add", project.toString()));
    assertEquals(0, commandLine.execute("cd", project.toString()));
    assertEquals(project.toAbsolutePath().normalize(), service.currentProject());
    assertEquals(0, commandLine.execute("remove", project.toString()));
    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void listMarksCurrentProject() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service =
        connect(new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects")));
    service.add(project.toString());
    service.cd(project.toString());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertEquals(0, newCommand(service).execute("list"));
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("* " + project.toAbsolutePath().normalize()));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private CommandLine newCommand(ProjectService service) {
    return new CommandLine(
        new ProjectCommand(),
        new CommandLine.IFactory() {
          /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
          @Override
          public <K> K create(Class<K> cls) throws Exception {
            if (cls == ProjectCommand.AddCommand.class) {
              return cls.cast(new ProjectCommand.AddCommand(service));
            }
            if (cls == ProjectCommand.RemoveCommand.class) {
              return cls.cast(new ProjectCommand.RemoveCommand(service));
            }
            if (cls == ProjectCommand.ListCommand.class) {
              return cls.cast(new ProjectCommand.ListCommand(service));
            }
            if (cls == ProjectCommand.CdCommand.class) {
              return cls.cast(new ProjectCommand.CdCommand(service));
            }
            return CommandLine.defaultFactory().create(cls);
          }
        });
  }
}
