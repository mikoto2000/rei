package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 状態表示利用者サービスの結果と状態の契約を検証するテスト。 */
class DotHumanServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うサービス。 */
  DotDelegationService service;

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 検証に使うプロジェクト一覧。 */
  ProjectService projects;

  /** 検証に使うプロジェクト。 */
  ProjectContext project;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, Clock.systemUTC());
    project =
        new ProjectContext(
            UUID.randomUUID().toString(), "p", Files.createDirectory(root.resolve("project")));
    projects = mock(ProjectService.class);
    when(projects.registeredProjects()).thenReturn(List.of(project));
    when(projects.currentContext()).thenReturn(project);
    var properties =
        new DotProperties(true, false, false, Set.of(project.id()), null, 0, 0, null, null, 0, 0);
    service = new DotDelegationService(repo, properties, projects, mock(SessionRepository.class));
  }

  /** 利用者の状態表示の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void explicitHumanRequestIsSanitizedAndCancelledThroughShell() {
    var command = new picocli.CommandLine(new DotCommand(service, projects));
    var output = new java.io.StringWriter();
    command.setOut(new java.io.PrintWriter(output));
    assertEquals(
        0,
        command.execute(
            "request",
            "Review C:\\private\\design.txt token=hidden",
            "--context",
            "/home/private/secret",
            "--category",
            "REVIEW"));
    assertFalse(output.toString().contains("hidden"));
    assertFalse(output.toString().contains("C:\\private"));
    assertFalse(output.toString().contains("/home/private"));
    var list = service.list(project.id());
    assertEquals(1, list.size());
    String id = (String) list.getFirst().get("request_id");
    assertEquals("CANCELLED", service.cancel(project.id(), id).get("status"));
  }

  /** 利用者の状態表示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void adminProjectCategoryAndSessionBoundariesAreChecked() {
    assertThrows(
        DotException.class,
        () -> service.createHuman("other", null, Map.of("instruction", "task")));
    assertThrows(
        DotException.class,
        () -> service.createHuman(project.id(), "missing", Map.of("instruction", "task")));
    assertThrows(
        DotException.class,
        () ->
            service.createHuman(
                project.id(), null, Map.of("instruction", "task", "category", "EXECUTE_SHELL")));
  }
}
