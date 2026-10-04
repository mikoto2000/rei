package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.temporal.*;
import dev.mikoto2000.rei.application.state.OperationConflictException;

class ScheduleControllerTest {
 @Test void readsNeverActivateAndReconciliationRequiresAcknowledgedExactRun() {
  var repo=mock(PersistentAgentScheduler.class);var dispatcher=mock(AgentScheduleDispatcher.class);
  var api=new ScheduleController(repo,dispatcher);
  api.list("p");api.show("p","timer");api.history("p","timer");verifyNoInteractions(dispatcher);
  assertThrows(IllegalArgumentException.class,()->api.reconcile("p","timer",new ScheduleController.ReconcileRequest("old",false)));
  assertThrows(IllegalArgumentException.class,()->api.reconcile("p","timer",new ScheduleController.ReconcileRequest("",true)));
  verifyNoInteractions(dispatcher);
  api.reconcile("p","timer",new ScheduleController.ReconcileRequest("old",true));
  verify(dispatcher).reconcile("p","timer","old");verifyNoMoreInteractions(dispatcher);
  verify(repo,never()).activate(anyString(),anyString());verify(repo,never()).claimDue();
 }
 @Test void mutationsUseExistingStateGuardsAndDoNotRetryConflicts() {
  var repo=mock(PersistentAgentScheduler.class);var dispatcher=mock(AgentScheduleDispatcher.class);
  var api=new ScheduleController(repo,dispatcher);
  doThrow(new IllegalStateException("already activated")).when(repo).activate("p","timer");
  assertThrows(OperationConflictException.class,()->api.activate("p","timer"));
  verify(repo,times(1)).activate("p","timer");
  when(dispatcher.reconcile("p","timer","old")).thenThrow(new IllegalStateException("queued"));
  assertThrows(OperationConflictException.class,()->api.reconcile("p","timer",new ScheduleController.ReconcileRequest("old",true)));
  verify(dispatcher,times(1)).reconcile("p","timer","old");
 }
}
