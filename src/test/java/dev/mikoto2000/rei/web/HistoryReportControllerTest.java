package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.HistoryPage;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.conversation.HistoryShellService;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryReportControllerTest {
  @TempDir Path directory;

  @Test
  void historyRenderingUsesRequestLocalProjectAndRestoresSelection() throws Exception {
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var selected =
        registry.resolve(java.nio.file.Files.createDirectory(directory.resolve("selected")));
    var projects = new ProjectService(directory, registry);
    var history = mock(HistoryShellService.class);
    var sessions = mock(SessionQueryService.class);
    when(sessions.listSessions(null, null, null)).thenReturn(new HistoryPage<>(List.of(), null));
    var controller = new HistoryReportController(history, sessions, projects, registry);
    assertEquals(
        "No sessions." + System.lineSeparator(), controller.sessions(null, null, null).text());
    doAnswer(
            call -> {
              assertEquals(selected.root(), projects.currentProject());
              @SuppressWarnings("unchecked")
              var output = (java.util.function.Consumer<String>) call.getArgument(3);
              output.accept("fixture");
              return null;
            })
        .when(history)
        .list(isNull(), eq(50), eq(0), any());
    assertEquals(
        "fixture" + System.lineSeparator(),
        controller.conversations(selected.id(), null, 50, 0).text());
    assertEquals(directory, projects.currentProject());
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> controller.conversations("foreign", null, 50, 0));
  }
}
