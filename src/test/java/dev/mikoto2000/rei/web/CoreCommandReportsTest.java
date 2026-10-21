package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.workcontext.WorkContextGit;
import dev.mikoto2000.rei.workcontext.WorkContextProperties;
import dev.mikoto2000.rei.workcontext.WorkContextService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Coreコマンド伝えるの結果と状態の契約を検証するテスト。 */
class CoreCommandReportsTest {
  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void checkpointLabelsMatchResumeListAndWorkEmptyHistoryHasShellMessage() {
    var service = mock(PersistentCheckpointService.class);
    var saved =
        PersistentCheckpoint.initial("task", "project", "session", "run", Path.of("."), "request");
    when(service.list("project")).thenReturn(List.of(saved));
    var checkpoints = new CheckpointController(service);
    assertEquals("[task [RUNNING] request]", checkpoints.reportList("project").text());
    var work = mock(WorkContextService.class);
    when(work.history("project", 100)).thenReturn(List.of());
    var api =
        new WorkContextController(
            work,
            mock(WorkContextGit.class),
            new WorkContextProperties(false, true, 1200, 12000, 120, 20));
    assertEquals("Work Context: 履歴はまだありません。", api.reportHistory("project").text());
  }
}
