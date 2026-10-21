package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.activity.ActivityCommand;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.core.project.ProjectClientScope;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 作業状況コマンド一覧受付窓口の結果と状態の契約を検証するテスト。 */
class ActivityCommandsControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** シェル対象範囲を復元することを確認する。 */
  @Test
  void contextTemporarilySelectsOnlyTheRequestProjectAndRestoresShellScope() throws Exception {
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var first = registry.resolve(Files.createDirectory(directory.resolve("first")));
    var second = registry.resolve(Files.createDirectory(directory.resolve("second")));
    var projects = new ProjectService(second.root(), registry);
    var command = mock(ActivityCommand.class);
    when(command.reportContext("today"))
        .thenAnswer(
            call -> {
              assertEquals(first.id(), projects.currentContext().id());
              return "context";
            });
    var api =
        new ActivityCommandsController(
            command, registry, projects, mock(IntegrationRunSubmitService.class));
    try (var scope = ProjectClientScope.open(projects.newClient())) {
      assertEquals(second.id(), projects.currentContext().id());
      assertEquals("context", api.context(first.id(), "today").text());
      assertEquals(second.id(), projects.currentContext().id());
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void coachingChangesRequireTheViewedRevision() {
    var command = mock(ActivityCommand.class);
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var api =
        new ActivityCommandsController(
            command,
            registry,
            new ProjectService(directory, registry),
            mock(IntegrationRunSubmitService.class));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.enabled(new ActivityCommandsController.Enable(true, null)));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(command);
    when(command.reportCoachingEnabled(true, 2)).thenReturn("enabled");
    assertEquals("enabled", api.enabled(new ActivityCommandsController.Enable(true, 2L)).text());
    verify(command).reportCoachingEnabled(true, 2);
  }
}
