package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.temporal.AgentScheduleDispatcher;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import org.junit.jupiter.api.Test;

/** 実行予定受付窓口の結果と状態の契約を検証するテスト。 */
class ScheduleControllerTest {
  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void readsNeverActivateAndReconciliationRequiresAcknowledgedExactRun() {
    var repo = mock(PersistentAgentScheduler.class);
    var dispatcher = mock(AgentScheduleDispatcher.class);
    var api = new ScheduleController(repo, dispatcher);
    api.list("p");
    api.show("p", "timer");
    api.history("p", "timer");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(dispatcher);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.reconcile("p", "timer", new ScheduleController.ReconcileRequest("old", false)));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.reconcile("p", "timer", new ScheduleController.ReconcileRequest("", true)));
    verifyNoInteractions(dispatcher);
    api.reconcile("p", "timer", new ScheduleController.ReconcileRequest("old", true));
    verify(dispatcher).reconcile("p", "timer", "old");
    verifyNoMoreInteractions(dispatcher);
    verify(repo, never()).activate(anyString(), anyString());
    verify(repo, never()).claimDue();
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mutationsUseExistingStateGuardsAndDoNotRetryConflicts() {
    var repo = mock(PersistentAgentScheduler.class);
    var dispatcher = mock(AgentScheduleDispatcher.class);
    var api = new ScheduleController(repo, dispatcher);
    doThrow(new IllegalStateException("already activated")).when(repo).activate("p", "timer");
    assertThrows(OperationConflictException.class, () -> api.activate("p", "timer"));
    verify(repo, times(1)).activate("p", "timer");
    when(dispatcher.reconcile("p", "timer", "old")).thenThrow(new IllegalStateException("queued"));
    assertThrows(
        OperationConflictException.class,
        () -> api.reconcile("p", "timer", new ScheduleController.ReconcileRequest("old", true)));
    verify(dispatcher, times(1)).reconcile("p", "timer", "old");
  }
}
