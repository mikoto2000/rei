package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.goal.*;
class GoalControllerTest {
 @Test void readsDoNotRunAndReconciliationRequiresExplicitAcknowledgement() {
  var repo=mock(GoalRepository.class);var loop=mock(GoalLoopService.class);var api=new GoalController(repo,loop);
  api.list("p");api.show("p","goal");api.history("p","goal");verifyNoInteractions(loop);
  assertThrows(IllegalArgumentException.class,()->api.reconcile("p","goal",new GoalController.ReconcileRequest("old",false)));
  verifyNoInteractions(loop);
  api.reconcile("p","goal",new GoalController.ReconcileRequest("old",true));verify(loop).reconcile("p","goal","old");verifyNoMoreInteractions(loop);
 }
 @Test void activeRunConflictsAreSafeAndNeverAutomaticallyRetried() {
  var loop=mock(GoalLoopService.class);var api=new GoalController(mock(GoalRepository.class),loop);
  when(loop.reconcile("p","goal","old")).thenThrow(new IllegalStateException("queued or executing"));
  assertThrows(dev.mikoto2000.rei.application.state.OperationConflictException.class,()->api.reconcile("p","goal",new GoalController.ReconcileRequest("old",true)));
  verify(loop,times(1)).reconcile("p","goal","old");verifyNoMoreInteractions(loop);
 }
}
