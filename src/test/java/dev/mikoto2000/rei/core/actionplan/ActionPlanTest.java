package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ActionPlanTest {

  @Test
  void emptyActionPlanHasNoSteps() {
    ActionPlan plan = new ActionPlan();
    assertTrue(plan.steps().isEmpty());
    assertTrue(plan.currentStep().isEmpty());
  }

  @Test
  void emptyActionPlanRendersBlank() {
    ActionPlan plan = new ActionPlan();
    assertEquals("", plan.renderForPrompt());
  }

  @Test
  void todoStepCanBeAdded() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    assertEquals(1, plan.steps().size());
    assertEquals("step-1", plan.steps().get(0).id());
    assertEquals(ActionPlan.STATUS_TODO, plan.steps().get(0).status());
  }

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

  @Test
  void todoCanTransitionToInProgress() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.startStep("step-1");
    assertEquals(ActionPlan.STATUS_IN_PROGRESS, plan.steps().get(0).status());
    assertTrue(plan.currentStep().isPresent());
  }

  @Test
  void inProgressCanTransitionToDone() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("再現テストを追加");
    plan.startStep("step-1");
    plan.completeStep("step-1");
    assertEquals(ActionPlan.STATUS_DONE, plan.steps().get(0).status());
    assertTrue(plan.currentStep().isEmpty());
  }

  @Test
  void inProgressCanTransitionToBlocked() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("実装修正");
    plan.startStep("step-1");
    plan.blockStep("step-1");
    assertEquals(ActionPlan.STATUS_BLOCKED, plan.steps().get(0).status());
  }

  @Test
  void todoCanTransitionToSkipped() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("不要な作業");
    plan.skipStep("step-1");
    assertEquals(ActionPlan.STATUS_SKIPPED, plan.steps().get(0).status());
  }

  @Test
  void onlyOneStepCanBeInProgress() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    plan.startStep("step-2");
    long inProgress = plan.steps().stream().filter(s -> ActionPlan.STATUS_IN_PROGRESS.equals(s.status())).count();
    assertEquals(1, inProgress);
  }

  @Test
  void currentStepCanBeRetrieved() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.startStep("step-1");
    assertEquals("step-1", plan.currentStep().get().id());
  }

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

  @Test
  void failureCountCanBeIncremented() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("実装修正");
    plan.incrementFailure("step-1");
    assertEquals(1, plan.steps().get(0).failureCount());
  }

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

  @Test
  void skippedStepsCountAsComplete() {
    ActionPlan plan = new ActionPlan();
    plan.addStep("step A");
    plan.addStep("step B");
    plan.skipStep("step-1");
    plan.skipStep("step-2");
    assertTrue(plan.isComplete());
  }

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
