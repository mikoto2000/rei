package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.ui.shell.ActiveRunDisplay;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveRunControllerTest {
  @TempDir Path directory;

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
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> controller.cancel("unknown"));
    verifyNoMoreInteractions(cancellation);
  }
}
