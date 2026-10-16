package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CheckpointCommandTest {
  private static CheckpointHistory.Usage usage() {
    return new CheckpointHistory.Usage(10, 30, 4096, 100, 8192);
  }

  private static CheckpointHistory.Cleanup cleanup(boolean applied) {
    return new CheckpointHistory.Cleanup(
        7, 2048, 2, usage(), new CheckpointHistory.Usage(3, 30, 2048, 100, 8192), applied, 3, 2048);
  }

  @Test
  void statusIsGlobalAndShowsBothCapacitySettingNames() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    when(service.historyUsage()).thenReturn(usage());
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, projects))
            .setOut(new PrintWriter(output, true));
    assertEquals(0, command.execute("status"));
    assertTrue(output.toString().contains("4096 / 8192"));
    assertTrue(output.toString().contains("10 / 100"));
    assertTrue(output.toString().contains("rei.checkpoint.max-bytes"));
    assertTrue(output.toString().contains("rei.checkpoint.max-revisions"));
    verifyNoInteractions(projects);
    verify(service).historyUsage();
    verifyNoMoreInteractions(service);
  }

  @Test
  void cleanupWithoutApplyOnlyPreviewsAndPrintsExactApplyCommand() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    when(service.cleanupHistory(false)).thenReturn(cleanup(false));
    var output = new StringWriter();
    var command = new CheckpointCommand(service, projects);
    command.setShellOutput(new PrintWriter(output, true));
    assertEquals(0, new CommandLine(command).execute("cleanup"));
    assertTrue(output.toString().contains("まだ削除していません"));
    assertTrue(output.toString().contains("7 件 / 2048 バイト"));
    assertTrue(output.toString().contains("/checkpoint cleanup --apply"));
    assertTrue(output.toString().contains("4096 / 8192"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 3 件 / 2048 バイト"));
    assertTrue(output.toString().contains("実行中・承認待ちのタスクでも不要な過去の履歴は古い順に整理できます"));
    verify(service).cleanupHistory(false);
    verifyNoMoreInteractions(service);
    verifyNoInteractions(projects);
  }

  @Test
  void explicitApplyDisplaysRemainingStorageAndPreservedEvidence() {
    var service = mock(PersistentCheckpointService.class);
    when(service.cleanupHistory(true)).thenReturn(cleanup(true));
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, mock(ProjectService.class)))
            .setOut(new PrintWriter(output, true));
    assertEquals(0, command.execute("cleanup", "--apply"));
    assertTrue(output.toString().contains("削除した履歴: 7 件"));
    assertTrue(output.toString().contains("2048 / 8192"));
    assertTrue(output.toString().contains("重複実行防止キー: 30 件"));
    assertTrue(output.toString().contains("各タスクの最新状態"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 3 件 / 2048 バイト"));
    verify(service).cleanupHistory(true);
    verifyNoMoreInteractions(service);
  }

  @Test
  void noCleanupCandidatesExplainsThatApplyWillNotRemoveProtectedHistory() {
    var service = mock(PersistentCheckpointService.class);
    when(service.cleanupHistory(false))
        .thenReturn(new CheckpointHistory.Cleanup(0, 0, 2, usage(), usage(), false, 10, 4096));
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, mock(ProjectService.class)))
            .setOut(new PrintWriter(output, true));
    assertEquals(0, command.execute("cleanup"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 10 件 / 4096 バイト"));
    assertTrue(output.toString().contains("削除可能な履歴はありません"));
    assertTrue(output.toString().contains("保護対象の履歴は /checkpoint cleanup --apply でも削除されません"));
    verify(service).cleanupHistory(false);
    verifyNoMoreInteractions(service);
  }

  @Test
  void legacyCleanupCallerKeepsUnknownProtectionTotalsDistinctFromZero() {
    var cleanup = new CheckpointHistory.Cleanup(0, 0, 2, usage(), usage(), false);
    assertEquals(-1, cleanup.protectedRevisions());
    assertEquals(-1, cleanup.protectedBytes());
    assertFalse(cleanup.force());
    assertFalse(cleanup(false).force());
    assertTrue(CheckpointHistoryReport.cleanup(cleanup).contains("保護されて削除できない履歴の件数・容量: 未取得"));
  }

  @Test
  void forceWithoutApplyPreviewsAllCheckpointsWithoutDeleting() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    var empty = new CheckpointHistory.Usage(0, 30, 0, 100, 8192);
    when(service.cleanupHistory(false, true))
        .thenReturn(new CheckpointHistory.Cleanup(10, 4096, 0, usage(), empty, false, 0, 0, true));
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, projects))
            .setOut(new PrintWriter(output, true));
    assertEquals(0, command.execute("cleanup", "--force"));
    String text = output.toString();
    assertTrue(text.contains("全チェックポイントの強制削除候補"));
    assertTrue(text.contains("まだ削除していません"));
    assertTrue(text.contains("削除対象の履歴: 10 件 / 4096 バイト"));
    assertTrue(text.contains("最新状態・再開元・承認続行用・実行中・承認待ち・破損した履歴も保護せず"));
    assertTrue(text.contains("/checkpoint cleanup --force --apply"));
    assertTrue(text.contains("4096 / 8192"));
    assertFalse(text.contains("各タスクの最新状態と再開・承認続行に必要な履歴、重複実行防止キーを保持"));
    verify(service).cleanupHistory(false, true);
    verifyNoMoreInteractions(service);
    verifyNoInteractions(projects);
  }

  @Test
  void forceApplyReportsLossOfResumeAndPreservedNonCheckpointData() {
    var service = mock(PersistentCheckpointService.class);
    var projects = mock(ProjectService.class);
    var empty = new CheckpointHistory.Usage(0, 30, 0, 100, 8192);
    when(service.cleanupHistory(true, true))
        .thenReturn(new CheckpointHistory.Cleanup(10, 4096, 0, usage(), empty, true, 0, 0, true));
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, projects))
            .setOut(new PrintWriter(output, true));
    assertEquals(0, command.execute("cleanup", "--apply", "--force"));
    String text = output.toString();
    assertTrue(text.contains("全チェックポイントを削除しました"));
    assertTrue(text.contains("削除した履歴: 10 件 / 4096 バイト"));
    assertTrue(text.contains("削除したタスクは /resume で再開できません"));
    assertTrue(text.contains("重複実行防止キーと承認・承認続行データは保持"));
    assertTrue(text.contains("実行中の Run は継続します"));
    assertTrue(text.contains("その Run が終了するまでチェックポイントの保存を停止"));
    assertTrue(text.contains("承認続行や新しい Run"));
    assertTrue(text.contains("0 / 8192"));
    assertTrue(text.contains("重複実行防止キー: 30 件"));
    assertFalse(text.contains("全チェックポイントを削除するには:"));
    verify(service).cleanupHistory(true, true);
    verifyNoMoreInteractions(service);
    verifyNoInteractions(projects);
  }

  @Test
  void unknownCleanupOptionDoesNotRunPreviewOrDeletion() {
    var service = mock(PersistentCheckpointService.class);
    var command =
        new CommandLine(new CheckpointCommand(service, mock(ProjectService.class)))
            .setErr(new PrintWriter(new StringWriter(), true));
    assertEquals(2, command.execute("cleanup", "--forced", "--apply"));
    verifyNoInteractions(service);
  }

  @Test
  void capacityFailuresArePrintedWithoutAStackTrace() {
    var service = mock(PersistentCheckpointService.class);
    when(service.historyUsage())
        .thenThrow(
            new CheckpointException(
                CheckpointException.Code.CAPACITY,
                "保存容量: 9000 / 8192 バイト。rei.checkpoint.max-bytes を変更してください。"));
    var output = new StringWriter();
    var command =
        new CommandLine(new CheckpointCommand(service, mock(ProjectService.class)))
            .setOut(new PrintWriter(output, true));
    assertEquals(2, command.execute("status"));
    assertTrue(output.toString().contains("[error] 保存容量: 9000 / 8192"));
    assertFalse(output.toString().contains("at dev."));
  }
}
