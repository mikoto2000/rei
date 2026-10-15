package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.goal.*;
import org.junit.jupiter.api.Test;

class GoalControllerTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  @Test
  void goalCreationUsesRegisteredProjectValidatedSessionAndWebRequestSource() {
    var projects =
        new dev.mikoto2000.rei.core.project.ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    var sessions = mock(dev.mikoto2000.rei.application.session.SessionLifecycle.class);
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    api.configureCreation(projects, sessions);
    api.create(
        project.id(),
        new GoalController.CreateRequest(
            "session", "objective", "out.txt", "a".repeat(64), null, null, 3, 20));
    verify(sessions).validate("session", project.id());
    var owner =
        org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.core.chat.AgentRunContext.class);
    verify(loop)
        .create(owner.capture(), eq("objective"), eq("out.txt"), eq("a".repeat(64)), eq(3), eq(20));
    assertEquals(
        dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB,
        owner.getValue().requestSource());
    assertEquals(root, owner.getValue().projectRoot());
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () ->
            api.create(
                "missing",
                new GoalController.CreateRequest(
                    "session", "objective", "out.txt", "a".repeat(64), null, null, 3, 20)));
  }

  @Test
  void goalCreationRefusesConflictingCriterionFormatsBeforeCallingLoop() {
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            api.create(
                "project",
                new GoalController.CreateRequest(
                    "session", "objective", "out.txt", "a".repeat(64), "[]", null, 3, 20)));
    verifyNoInteractions(loop);
  }

  @Test
  void authenticatedHumanEndpointsDelegateCompletionDefinitionAndProofWithoutDispatching() {
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    var definition =
        new GoalCompletionGate.Definition(
            java.util.List.of(new GoalRepository.FileCriterion("out.txt", "a".repeat(64))),
            null,
            java.util.List.of(),
            java.util.List.of(),
            null);
    var proof = new GoalCompletionGate.Proof(null, java.util.List.of());
    api.defineCompletion("p", "goal", definition);
    api.attachCompletion("p", "goal", proof);
    verify(loop).defineCompletion("p", "goal", definition);
    verify(loop).attachCompletion("p", "goal", proof);
    verifyNoMoreInteractions(loop);
  }

  @Test
  void readsDoNotRunAndReconciliationRequiresExplicitAcknowledgement() {
    var repo = mock(GoalRepository.class);
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(repo, loop);
    api.list("p");
    api.show("p", "goal");
    api.history("p", "goal");
    verifyNoInteractions(loop);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.reconcile("p", "goal", new GoalController.ReconcileRequest("old", false)));
    verifyNoInteractions(loop);
    api.reconcile("p", "goal", new GoalController.ReconcileRequest("old", true));
    verify(loop).reconcile("p", "goal", "old");
    verifyNoMoreInteractions(loop);
  }

  @Test
  void activeRunConflictsAreSafeAndNeverAutomaticallyRetried() {
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    when(loop.reconcile("p", "goal", "old"))
        .thenThrow(new IllegalStateException("queued or executing"));
    assertThrows(
        dev.mikoto2000.rei.application.state.OperationConflictException.class,
        () -> api.reconcile("p", "goal", new GoalController.ReconcileRequest("old", true)));
    verify(loop, times(1)).reconcile("p", "goal", "old");
    verifyNoMoreInteractions(loop);
  }

  @Test
  void waitControlsUseExplicitVersionAndNeverDispatchThroughNormalRun() {
    var loop = mock(GoalLoopService.class);
    var waits = mock(GoalWaitService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    api.configureWaits(waits);
    api.waitFor("p", "g", new GoalController.WaitRequest("dep", "job_wait"));
    api.waiting("p", "g");
    api.resumeWait("p", "g", new GoalController.WaitVersion(3));
    api.activateWait("p", "g", new GoalController.WaitVersion(4));
    verify(waits).waitFor("p", "g", "dep", "job_wait");
    verify(waits).show("p", "g");
    verify(waits).resume("p", "g", 3);
    verify(waits).activate("p", "g", 4);
    verifyNoInteractions(loop);
  }

  @Test
  void selectedCliSessionMustOwnGoalBeforeCreatingWait() {
    var repo = mock(GoalRepository.class);
    var goal = mock(GoalRepository.Goal.class);
    when(repo.get("p", "g")).thenReturn(goal);
    when(goal.sessionId()).thenReturn("owner");
    var waits = mock(GoalWaitService.class);
    var api = new GoalController(repo, mock(GoalLoopService.class));
    api.configureWaits(waits);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.waitFor("p", "g", new GoalController.WaitRequest("dep", "job_wait", "other")));
    verifyNoInteractions(waits);
    api.waitFor("p", "g", new GoalController.WaitRequest("dep", "job_wait", "owner"));
    verify(waits).waitFor("p", "g", "dep", "job_wait");
  }
}
