package dev.mikoto2000.rei.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.runmanagement.RunHistoryEntry;
import dev.mikoto2000.rei.runmanagement.RunManagement;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunsHistoryCommandTest {
  @Test
  void historyUsesGlobalScopeOnlyWhenRequestedAndPrintsRetainedRun() {
    var service = mock(RunManagement.class);
    var projects = mock(ProjectService.class);
    var project = new ProjectContext("00000000-0000-0000-0000-000000000001", "One", Path.of("."));
    when(projects.currentContext()).thenReturn(project);
    when(service.history(null, "completed", 3))
        .thenReturn(
            List.of(
                new RunHistoryEntry(
                    project.id(),
                    "One",
                    "run",
                    "Request",
                    "COMPLETED",
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(10),
                    "")));
    var runs = new RunsCommand(mock(ActiveRunDisplay.class));
    runs.setManagement(service);
    when(service.currentProjectId()).thenReturn(project.id());
    var output = new StringWriter();
    var cli = new picocli.CommandLine(runs).setOut(new PrintWriter(output));
    assertEquals(0, cli.execute("history", "--status", "completed", "--limit", "3", "--all"));
    verify(service).history(null, "completed", 3);
    assertTrue(output.toString().contains("One  run  COMPLETED"));
    assertEquals(0, cli.execute("history"));
    verify(service).history(project.id(), null, 20);
  }
}
