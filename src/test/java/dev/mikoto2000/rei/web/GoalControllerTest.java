package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.goal.GoalCompletionGate;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.goal.GoalWaitService;
import org.junit.jupiter.api.Test;

/** 目標受付窓口の結果と状態の契約を検証するテスト。 */
class GoalControllerTest {
  /** 検証に使う作業ルート。 */
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;

  /** 登録済みプロジェクト検証済み会話を利用することを確認する。 */
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

  /** Web APIによる操作で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(loop);
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** なし実行を読み取ることを確認する。 */
  @Test
  void readsDoNotRunAndReconciliationRequiresExplicitAcknowledgement() {
    var repo = mock(GoalRepository.class);
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(repo, loop);
    api.list("p");
    api.show("p", "goal");
    api.history("p", "goal");
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(loop);
    assertThrows(
        IllegalArgumentException.class,
        () -> api.reconcile("p", "goal", new GoalController.ReconcileRequest("old", false)));
    verifyNoInteractions(loop);
    api.reconcile("p", "goal", new GoalController.ReconcileRequest("old", true));
    verify(loop).reconcile("p", "goal", "old");
    verifyNoMoreInteractions(loop);
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
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

  /** 明示的な形式のバージョンを利用することを確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(loop);
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
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
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(waits);
    api.waitFor("p", "g", new GoalController.WaitRequest("dep", "job_wait", "owner"));
    verify(waits).waitFor("p", "g", "dep", "job_wait");
  }
}
