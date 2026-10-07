package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.goal.*;
class GoalControllerTest {
 @Test void authenticatedHumanEndpointsDelegateCompletionDefinitionAndProofWithoutDispatching() {
  var loop=mock(GoalLoopService.class);var api=new GoalController(mock(GoalRepository.class),loop);
  var definition=new GoalCompletionGate.Definition(java.util.List.of(new GoalRepository.FileCriterion("out.txt","a".repeat(64))),null,java.util.List.of(),java.util.List.of(),null);var proof=new GoalCompletionGate.Proof(null,java.util.List.of());
  api.defineCompletion("p","goal",definition);api.attachCompletion("p","goal",proof);verify(loop).defineCompletion("p","goal",definition);verify(loop).attachCompletion("p","goal",proof);verifyNoMoreInteractions(loop);
 }
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
