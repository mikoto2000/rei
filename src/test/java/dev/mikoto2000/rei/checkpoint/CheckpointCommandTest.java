package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.project.ProjectService;
import picocli.CommandLine;

class CheckpointCommandTest {
  private static CheckpointHistory.Usage usage(){return new CheckpointHistory.Usage(10,30,4096,100,8192);}
  private static CheckpointHistory.Cleanup cleanup(boolean applied){return new CheckpointHistory.Cleanup(7,2048,2,usage(),new CheckpointHistory.Usage(3,30,2048,100,8192),applied,3,2048);}

  @Test void statusIsGlobalAndShowsBothCapacitySettingNames(){
    var service=mock(PersistentCheckpointService.class);var projects=mock(ProjectService.class);when(service.historyUsage()).thenReturn(usage());
    var output=new StringWriter();var command=new CommandLine(new CheckpointCommand(service,projects)).setOut(new PrintWriter(output,true));
    assertEquals(0,command.execute("status"));
    assertTrue(output.toString().contains("4096 / 8192"));assertTrue(output.toString().contains("10 / 100"));
    assertTrue(output.toString().contains("rei.checkpoint.max-bytes"));assertTrue(output.toString().contains("rei.checkpoint.max-revisions"));
    verifyNoInteractions(projects);verify(service).historyUsage();verifyNoMoreInteractions(service);
  }

  @Test void cleanupWithoutApplyOnlyPreviewsAndPrintsExactApplyCommand(){
    var service=mock(PersistentCheckpointService.class);var projects=mock(ProjectService.class);when(service.cleanupHistory(false)).thenReturn(cleanup(false));
    var output=new StringWriter();var command=new CheckpointCommand(service,projects);command.setShellOutput(new PrintWriter(output,true));
    assertEquals(0,new CommandLine(command).execute("cleanup"));
    assertTrue(output.toString().contains("まだ削除していません"));assertTrue(output.toString().contains("7 件 / 2048 バイト"));
    assertTrue(output.toString().contains("/checkpoint cleanup --apply"));assertTrue(output.toString().contains("4096 / 8192"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 3 件 / 2048 バイト"));
    assertTrue(output.toString().contains("実行中・承認待ちのタスクでも不要な過去の履歴は古い順に整理できます"));
    verify(service).cleanupHistory(false);verifyNoMoreInteractions(service);verifyNoInteractions(projects);
  }

  @Test void explicitApplyDisplaysRemainingStorageAndPreservedEvidence(){
    var service=mock(PersistentCheckpointService.class);when(service.cleanupHistory(true)).thenReturn(cleanup(true));
    var output=new StringWriter();var command=new CommandLine(new CheckpointCommand(service,mock(ProjectService.class))).setOut(new PrintWriter(output,true));
    assertEquals(0,command.execute("cleanup","--apply"));
    assertTrue(output.toString().contains("削除した履歴: 7 件"));assertTrue(output.toString().contains("2048 / 8192"));
    assertTrue(output.toString().contains("重複実行防止キー: 30 件"));assertTrue(output.toString().contains("各タスクの最新状態"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 3 件 / 2048 バイト"));
    verify(service).cleanupHistory(true);verifyNoMoreInteractions(service);
  }

  @Test void noCleanupCandidatesExplainsThatApplyWillNotRemoveProtectedHistory(){
    var service=mock(PersistentCheckpointService.class);
    when(service.cleanupHistory(false)).thenReturn(new CheckpointHistory.Cleanup(0,0,2,usage(),usage(),false,10,4096));
    var output=new StringWriter();
    var command=new CommandLine(new CheckpointCommand(service,mock(ProjectService.class))).setOut(new PrintWriter(output,true));
    assertEquals(0,command.execute("cleanup"));
    assertTrue(output.toString().contains("保護されて削除できない履歴: 10 件 / 4096 バイト"));
    assertTrue(output.toString().contains("削除可能な履歴はありません"));
    assertTrue(output.toString().contains("保護対象の履歴は /checkpoint cleanup --apply でも削除されません"));
    verify(service).cleanupHistory(false);
    verifyNoMoreInteractions(service);
  }

  @Test void legacyCleanupCallerKeepsUnknownProtectionTotalsDistinctFromZero(){
    var cleanup=new CheckpointHistory.Cleanup(0,0,2,usage(),usage(),false);
    assertEquals(-1,cleanup.protectedRevisions());
    assertEquals(-1,cleanup.protectedBytes());
    assertTrue(CheckpointHistoryReport.cleanup(cleanup).contains("保護されて削除できない履歴の件数・容量: 未取得"));
  }

  @Test void capacityFailuresArePrintedWithoutAStackTrace(){
    var service=mock(PersistentCheckpointService.class);when(service.historyUsage()).thenThrow(new CheckpointException(CheckpointException.Code.CAPACITY,"保存容量: 9000 / 8192 バイト。rei.checkpoint.max-bytes を変更してください。"));
    var output=new StringWriter();var command=new CommandLine(new CheckpointCommand(service,mock(ProjectService.class))).setOut(new PrintWriter(output,true));
    assertEquals(2,command.execute("status"));assertTrue(output.toString().contains("[error] 保存容量: 9000 / 8192"));assertFalse(output.toString().contains("at dev."));
  }
}
