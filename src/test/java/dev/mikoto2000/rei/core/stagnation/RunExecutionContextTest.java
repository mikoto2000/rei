package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class RunExecutionContextTest {
  @Test
  void completedSubgoalRecoversOnceButDoesNotReplenishHardBudget() {
    var budget = new OutputLimitRunBudget(1, 30);
    List<AgentEvent> events = new ArrayList<>();
    var context = new RunExecutionContext("run", budget, new ProgressEvaluator(Path.of(".")),
        new AgentEventFactory(Clock.systemUTC()), events::add);
    for (int i = 0; i < 4; i++) { context.beginIteration(); context.endIteration(); }
    assertThat(context.requestReplan()).contains("No-progress iterations: 4");
    context.completeSubgoal("verify build");
    assertThat(context.detector().replanCount()).isZero();
    context.beginIteration(); context.endIteration();
    context.completeSubgoal("verify build");
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
    for (int i = 0; i < 3; i++) { context.beginIteration(); context.endIteration(); }
    assertThatThrownBy(context::requestReplan).hasMessageContaining("REPLAN_BUDGET_EXCEEDED");
    assertThat(budget.replanCount()).isEqualTo(1);
    assertThat(events).anyMatch(e -> e.type() == AgentEventType.STAGNATION_RECOVERED);
    assertThat(events.stream().filter(e -> e.type() == AgentEventType.PROGRESS_DETECTED).count()).isEqualTo(1);
  }
}
