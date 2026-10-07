package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class RunExecutionContextTest {
  @Test void restrictedModeCannotBypassPermissionWhenNoGuardIsInstalled() {
    var context=new RunExecutionContext("run",new OutputLimitRunBudget(1,10),new ProgressEvaluator(Path.of(".")),new AgentEventFactory(Clock.systemUTC()),event->{});
    context.setRunContext(new dev.mikoto2000.rei.core.chat.AgentRunContext("run","session",Path.of("."),"A",
        dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB,dev.mikoto2000.rei.core.chat.AgentRunContext.Mode.READ_ONLY));
    assertThatThrownBy(()->context.checkToolPermission("runCommand","{}")).isInstanceOf(SecurityException.class);
    assertThatCode(()->context.checkToolPermission("readMultiFile","{}")).doesNotThrowAnyException();
  }
  @Test void mixedNoProgressActionsStillCountAndTerminalObservationIsProgressOnce() {
    var context=new RunExecutionContext("run",new OutputLimitRunBudget(1,10),new ProgressEvaluator(Path.of(".")),new AgentEventFactory(Clock.systemUTC()),event->{});
    context.beginIteration();
    context.observeDependency(new dev.mikoto2000.rei.core.dependency.DependencyObservation("p",dev.mikoto2000.rei.core.dependency.DependencyState.WAITING,""));
    context.recordTool("waitForShellProcess","{}","waiting",context.evaluator().beforeTool("waitForShellProcess","{}"));
    context.recordTool("noop","{}","same",context.evaluator().beforeTool("noop","{}"));context.endIteration();
    assertThat(context.detector().stagnationCount()).isEqualTo(1);
    context.beginIteration();context.observeDependency(new dev.mikoto2000.rei.core.dependency.DependencyObservation("p",dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED,""));context.endIteration();
    assertThat(context.detector().stagnationCount()).isZero();assertThat(context.progressVersion()).isEqualTo(1);
    context.beginIteration();context.observeDependency(new dev.mikoto2000.rei.core.dependency.DependencyObservation("p",dev.mikoto2000.rei.core.dependency.DependencyState.COMPLETED,""));context.endIteration();
    assertThat(context.detector().stagnationCount()).isEqualTo(1);assertThat(context.progressVersion()).isEqualTo(1);
  }
  @Test void verifiedWaitingDoesNotCountAsStagnationOrReplenishBudget() {
    var budget=new OutputLimitRunBudget(1,3);budget.tryConsumeLlmCall();
    var events=new ArrayList<AgentEvent>();
    var context=new RunExecutionContext("run",budget,new ProgressEvaluator(Path.of(".")),new AgentEventFactory(Clock.systemUTC()),events::add);
    for(int i=0;i<6;i++) {
      context.beginIteration();
      context.observeDependency(new dev.mikoto2000.rei.core.dependency.DependencyObservation("p",dev.mikoto2000.rei.core.dependency.DependencyState.WAITING,"running"));
      context.recordTool("waitForShellProcess","{}","waiting",context.evaluator().beforeTool("waitForShellProcess","{}"));context.endIteration();
    }
    assertThat(context.detector().stagnationCount()).isZero();assertThat(context.progressVersion()).isZero();
    context.consumeNextLlmCall();context.consumeNextLlmCall();
    assertThatThrownBy(context::consumeNextLlmCall).hasMessageContaining("LLM_CALL_BUDGET_EXCEEDED");
    assertThat(events).anyMatch(e->e.payload() instanceof ExecutionProgressPayload p && "waiting_for_dependency".equals(p.reason()));
  }
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
