package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.ui.shell.ActiveRunDisplay;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 活動中実行受付窓口の結果と状態の契約を検証するテスト。 */
class ActiveRunControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void listIsGlobalAndExplicitCancellationOnlyTargetsARegisteredProject() {
    var display = mock(ActiveRunDisplay.class);
    var cancellation = mock(CommandCancellationService.class);
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var project = registry.resolve(directory);
    var controller = new ActiveRunController(display, cancellation, registry);
    when(display.rows()).thenReturn(List.of("One  CHAT  RUNNING  00:02  request"));
    assertTrue(controller.list().text().contains("PROJECT  TYPE  STATUS  ELAPSED  REQUEST"));
    when(cancellation.cancelProject(project.id())).thenReturn(true);
    assertTrue(controller.cancel(project.id()).accepted());
    verify(cancellation).cancelProject(project.id());
    verify(cancellation).projectDiagnostics(project.id());
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> controller.cancel("unknown"));
    verifyNoMoreInteractions(cancellation);
  }
}
