package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.storage.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StorageReportsTest {
  @Test
  void planUsesTheCapturedProjectRatherThanBackendShellSelection() {
    var planner = mock(RetentionPlanner.class);
    String project = UUID.randomUUID().toString();
    var plan =
        new RetentionPlanner.Plan(
            "plan",
            "raw-results",
            1,
            project,
            "hash",
            List.of(),
            0,
            Map.of(),
            false,
            Instant.now());
    when(planner.plan("raw-results", project, 100, 16777216)).thenReturn(plan);
    var command =
        new StorageCommand(
            planner,
            mock(RetentionExecutor.class),
            mock(StorageMaintenance.class),
            mock(AutomaticRetention.class));
    try (var scope =
        AgentRunScope.open(
            new AgentRunContext(
                "run",
                "session",
                java.nio.file.Path.of("."),
                project,
                AgentRunContext.RequestSource.WEB))) {
      assertTrue(
          command.reportPlan("raw-results", "current", 100, 16777216).contains("scope=" + project));
    }
    verify(planner).plan("raw-results", project, 100, 16777216);
  }

  @Test
  void maintenanceAndSeparatePurgeApprovalUseTheExistingSafetyServices() {
    var maintenance = mock(StorageMaintenance.class);
    var executor = mock(RetentionExecutor.class);
    var command =
        new StorageCommand(
            mock(RetentionPlanner.class), executor, maintenance, mock(AutomaticRetention.class));
    when(maintenance.checkpoint()).thenReturn(new StorageMaintenance.Checkpoint(1, 12, 5));
    assertEquals("busy=1 log_frames=12 checkpointed_frames=5", command.reportCheckpoint());
    when(maintenance.incrementalVacuum(8)).thenReturn(false);
    assertEquals("incremental_vacuum_supported=false", command.reportIncrementalVacuum(8));
    assertEquals("purge approved execution=execution", command.reportApprovePurge("execution"));
    verify(executor).approvePurge("execution");
    verify(executor, never()).purge(any());
    assertEquals("VACUUM completed", command.reportVacuum(30));
    verify(maintenance).vacuum(Duration.ofSeconds(30));
  }
}
