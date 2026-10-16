package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.CheckpointHistory;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import org.junit.jupiter.api.Test;

class CheckpointHistoryControllerTest {
  private static CheckpointHistory.Usage usage() {
    return new CheckpointHistory.Usage(10, 30, 4096, 100, 8192);
  }

  private static CheckpointHistory.Cleanup cleanup(boolean applied) {
    return new CheckpointHistory.Cleanup(
        7, 2048, 2, usage(), new CheckpointHistory.Usage(3, 30, 2048, 100, 8192), applied);
  }

  @Test
  void statusAndPreviewOnlyUseReadOnlyCalls() {
    var service = mock(PersistentCheckpointService.class);
    when(service.historyUsage()).thenReturn(usage());
    when(service.cleanupHistory(false)).thenReturn(cleanup(false));
    var controller = new CheckpointHistoryController(service);
    assertTrue(controller.status().text().contains("rei.checkpoint.max-bytes"));
    assertTrue(controller.preview().text().contains("/checkpoint cleanup --apply"));
    verify(service).historyUsage();
    verify(service).cleanupHistory(false);
    verifyNoMoreInteractions(service);
  }

  @Test
  void cleanupRequiresExplicitTrueAndNeverAppliesForAMissingOrFalseRequest() {
    var service = mock(PersistentCheckpointService.class);
    var controller = new CheckpointHistoryController(service);
    assertThrows(IllegalArgumentException.class, () -> controller.apply(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.apply(new CheckpointHistoryController.ApplyRequest(false)));
    verifyNoInteractions(service);
  }

  @Test
  void explicitApplyReturnsSharedTerminalReport() {
    var service = mock(PersistentCheckpointService.class);
    when(service.cleanupHistory(true)).thenReturn(cleanup(true));
    var controller = new CheckpointHistoryController(service);
    assertTrue(
        controller
            .apply(new CheckpointHistoryController.ApplyRequest(true))
            .text()
            .contains("削除した履歴: 7 件"));
    verify(service).cleanupHistory(true);
    verifyNoMoreInteractions(service);
  }
}
