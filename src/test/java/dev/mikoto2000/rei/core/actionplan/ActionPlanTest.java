package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 操作計画の結果と状態の契約を検証するテスト。 */
class ActionPlanTest {

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptyActionPlanHasNoSteps() {
    ActionPlan plan = new ActionPlan();
    assertTrue(plan.steps().isEmpty());
    assertTrue(plan.currentStep().isEmpty());
  }

  /** 行動計画の管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void emptyActionPlanRendersBlank() {
    ActionPlan plan = new ActionPlan();
    assertEquals("", plan.renderForPrompt());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void todoStepCanBeAdded() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    assertEquals(1, plan.steps().size());
    assertEquals("step-1", plan.steps().get(0).id());
    assertEquals(ActionPlan.STATUS_TODO, plan.steps().get(0).status());
  }

  /** 行動計画の管理で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void stepOrderIsPreserved() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.addStep("実装修正");
    plan.addStep("全体テスト");
    assertEquals(3, plan.steps().size());
    assertEquals("step-1", plan.steps().get(0).id());
    assertEquals("step-2", plan.steps().get(1).id());
    assertEquals("step-3", plan.steps().get(2).id());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void todoCanTransitionToInProgress() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.startStep("step-1");
    assertEquals(ActionPlan.STATUS_IN_PROGRESS, plan.steps().get(0).status());
    assertTrue(plan.currentStep().isPresent());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void inProgressCanTransitionToDone() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.startStep("step-1");
    plan.completeStep("step-1");
    assertEquals(ActionPlan.STATUS_DONE, plan.steps().get(0).status());
    assertTrue(plan.currentStep().isEmpty());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void inProgressCanTransitionToBlocked() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("実装修正");
    plan.startStep("step-1");
    plan.blockStep("step-1");
    assertEquals(ActionPlan.STATUS_BLOCKED, plan.steps().get(0).status());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void todoCanTransitionToSkipped() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("不要な作業");
    plan.skipStep("step-1");
    assertEquals(ActionPlan.STATUS_SKIPPED, plan.steps().get(0).status());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void onlyOneStepCanBeInProgress() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    plan.startStep("step-2");
    long inProgress =
        plan.steps().stream().filter(s -> ActionPlan.STATUS_IN_PROGRESS.equals(s.status())).count();
    assertEquals(1, inProgress);
  }

  /** 行動計画の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void currentStepCanBeRetrieved() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    assertEquals("step-1", plan.currentStep().get().id());
  }

  /** 行動計画の管理で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void nextStepCanBeRetrieved() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    plan.completeStep("step-1");
    plan.startStep("step-2");
    assertEquals("step-2", plan.currentStep().get().id());
  }

  /** 行動計画の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureCountCanBeIncremented() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("実装修正");
    plan.incrementFailure("step-1");
    assertEquals(1, plan.steps().get(0).failureCount());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void planCompletionIsDetected() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    plan.completeStep("step-1");
    plan.startStep("step-2");
    plan.completeStep("step-2");
    assertTrue(plan.isComplete());
  }

  /** 行動計画の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void skippedStepsCountAsComplete() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.skipStep("step-1");
    plan.skipStep("step-2");
    assertTrue(plan.isComplete());
  }

  /** 行動計画の管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void planRendersSteps() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.addStep("実装修正");
    plan.startStep("step-2");
    String prompt = plan.renderForPrompt();
    assertTrue(prompt.contains("## Action Plan"));
    assertTrue(prompt.contains("1. [TODO] 再現テストを追加"));
    assertTrue(prompt.contains("2. [IN_PROGRESS] 実装修正"));
  }
}
