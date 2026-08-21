package dev.mikoto2000.rei.core.actionplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
