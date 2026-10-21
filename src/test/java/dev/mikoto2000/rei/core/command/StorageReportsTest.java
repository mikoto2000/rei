package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.storage.AutomaticRetention;
import dev.mikoto2000.rei.storage.RetentionExecutor;
import dev.mikoto2000.rei.storage.RetentionPlanner;
import dev.mikoto2000.rei.storage.StorageMaintenance;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 保存伝えるの結果と状態の契約を検証するテスト。 */
class StorageReportsTest {
  /** 計画について、取得済みプロジェクトを利用することを確認する。 */
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

  /** 共通の実行基盤の操作が、指定された権限・承認条件に従うことを確認する。 */
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
