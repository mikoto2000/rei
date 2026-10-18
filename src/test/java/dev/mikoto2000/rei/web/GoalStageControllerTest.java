package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.goal.GoalRepository;
import org.junit.jupiter.api.Test;

class GoalStageControllerTest {
  @Test
  void stagesUseExplicitVersionAndNeverDispatchFromReadsOrPlanWrites() {
    var loop = mock(GoalLoopService.class);
    var api = new GoalController(mock(GoalRepository.class), loop);
    api.stages("p", "g");
    var plan =
        java.util.List.of(
            new dev.mikoto2000.rei.goal.GoalStageDefinition(
                "a",
                null,
                "Produce a",
                java.util.List.of(new GoalRepository.FileCriterion("a.txt", "a".repeat(64))),
                java.util.List.of(),
                java.util.List.of(),
                null));
    api.defineStages("p", "g", new GoalController.StagePlanRequest(0, plan));
    verify(loop).stageProgress("p", "g");
    verify(loop).defineStages("p", "g", 0, plan);
    verifyNoMoreInteractions(loop);
    assertThrows(IllegalArgumentException.class, () -> api.defineStages("p", "g", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> api.defineStages("p", "g", new GoalController.StagePlanRequest(-1, plan)));
  }
}
