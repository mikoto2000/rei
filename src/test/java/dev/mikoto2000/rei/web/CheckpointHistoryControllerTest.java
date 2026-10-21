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

/** 再開用の記録履歴受付窓口の結果と状態の契約を検証するテスト。 */
class CheckpointHistoryControllerTest {
  /** {@code usage} の条件を再現するための補助情報を返す。 */
  private static CheckpointHistory.Usage usage() {
    return new CheckpointHistory.Usage(10, 30, 4096, 100, 8192);
  }

  /** 検証で使用した資源を解放し、後続の検証に影響を残さないようにする。 */
  private static CheckpointHistory.Cleanup cleanup(boolean applied) {
    return new CheckpointHistory.Cleanup(
        7, 2048, 2, usage(), new CheckpointHistory.Usage(3, 30, 2048, 100, 8192), applied);
  }

  /** 状態および事前確認限定について、読み取りを利用することを確認する。 */
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

  /** 後片付けについて、明示的な有効を必要とすることを確認する。 */
  @Test
  void cleanupRequiresExplicitTrueAndNeverAppliesForAMissingOrFalseRequest() {
    var service = mock(PersistentCheckpointService.class);
    var controller = new CheckpointHistoryController(service);
    assertThrows(IllegalArgumentException.class, () -> controller.apply(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.apply(new CheckpointHistoryController.ApplyRequest(false)));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(service);
  }

  /** 明示的な反映について、共有終端レポートを返すことを確認する。 */
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
