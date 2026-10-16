package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.command.StorageCommand;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageControllerTest {
  @TempDir Path directory;

  @Test
  void scopedPlanCapturesRegisteredProjectWithoutChangingShellSelection() {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var command = mock(StorageCommand.class);
    when(command.reportPlan("raw-results", "current", 100, 16777216))
        .thenAnswer(
            call -> {
              assertEquals(project.id(), AgentRunScope.current().projectId());
              return "plan";
            });
    var api = new StorageController(command, projects);
    assertEquals(
        "plan", api.plan(new StorageController.Plan(project.id(), true, null, null, null)).text());
    assertNull(AgentRunScope.current());
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> api.plan(new StorageController.Plan("foreign", true, null, null, null)));
    verify(command, times(1)).reportPlan(any(), any(), anyInt(), anyLong());
  }

  @Test
  void purgeApprovalAndPurgeRemainDifferentNamedOperations() {
    var command = mock(StorageCommand.class);
    var api =
        new StorageController(command, new ProjectRegistry(directory.resolve("projects.json")));
    when(command.reportApprovePurge("execution")).thenReturn("approved");
    assertEquals("approved", api.approvePurge("execution").text());
    verify(command).reportApprovePurge("execution");
    verify(command, never()).reportPurge(any());
    assertThrows(
        IllegalArgumentException.class, () -> api.vacuum(new StorageController.Window(null)));
  }
}
