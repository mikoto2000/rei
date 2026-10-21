package dev.mikoto2000.rei.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクトサービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectServiceTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** プロジェクトごとの作業環境で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void startsWithStartupDirectoryAsCurrentProject() {
    ProjectService service = newService();

    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
    assertTrue(service.list().contains(tempDir.toAbsolutePath().normalize()));
  }

  /** プロジェクトごとの作業環境で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void addRegistersNormalizedDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();

    Path added = service.add("project-a");

    assertEquals(project.toAbsolutePath().normalize(), added);
    assertTrue(service.list().contains(project.toAbsolutePath().normalize()));
  }

  /** プロジェクトごとの作業環境で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void cdChangesCurrentProjectOnlyForRegisteredDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();
    service.add(project.toString());

    service.cd(project.toString());

    assertEquals(project.toAbsolutePath().normalize(), service.currentProject());
  }

  /** プロジェクトごとの作業環境で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void cdRegistersUnregisteredDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();

    assertEquals(project.toRealPath(), service.cd(project.toString()));
  }

  /** 現在プロジェクト返すを取り除くことを確認する。 */
  @Test
  void removeCurrentProjectReturnsToStartupDirectory() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService service = newService();
    service.add(project.toString());
    service.cd(project.toString());

    service.remove(project.toString());

    assertEquals(tempDir.toAbsolutePath().normalize(), service.currentProject());
    assertTrue(!service.list().contains(project.toAbsolutePath().normalize()));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private ProjectService newService() {
    return connect(new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects")));
  }
}
