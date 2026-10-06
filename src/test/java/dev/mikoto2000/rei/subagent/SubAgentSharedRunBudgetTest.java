package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;

class SubAgentSharedRunBudgetTest {
  @TempDir Path directory;
  SubAgentRunnerTest fixture(){var result=new SubAgentRunnerTest();result.directory=directory;return result;}
  RunExecutionContext context(OutputLimitRunBudget budget){return new RunExecutionContext("parent",budget,null,null,null);}
  @Test void normalChatParentAndChildConsumeOneLocalBudgetWithoutGoal() throws Exception {
    var fixture=fixture();var calls=new AtomicInteger();
    var child=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));},"2s");
    var budget=new OutputLimitRunBudget(0,2);var execution=context(budget);
    execution.consumeNextLlmCall();
    var tools=new SubAgentTools(child,null);var toolContext=new ToolContext(Map.of(RunExecutionContext.KEY,execution));
    assertThat(tools.delegateTask("reviewer","inspect",null,toolContext).status()).isEqualTo(SubAgentResult.Status.COMPLETED);
    assertThat(budget.remainingLlmCalls()).isZero();
    var stopped=tools.delegateTask("reviewer","inspect",null,toolContext);
    assertThat(stopped.status()).isEqualTo(SubAgentResult.Status.FAILED);
    assertThat(stopped.output()).contains("SHARED_LLM_BUDGET_EXHAUSTED");
    assertThat(calls).hasValue(1);
  }
  @Test void parallelNormalChatChildrenCompeteForOneRemainingLocalCall() throws Exception {
    var fixture=fixture();var calls=new AtomicInteger();
    var child=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));},"2s");
    var registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(fixture.policy,model->true));registry.reload();
    var budget=new OutputLimitRunBudget(0,1);var execution=context(budget);
    try(var parallel=new ParallelSubAgentDelegator(child,registry,fixture.cancellation)) {
      var tools=new SubAgentTools(child,registry);tools.parallelDelegator(parallel);
      var batch=tools.delegateTasks(List.of(new ParallelSubAgentDelegator.Request("one","reviewer","one",null),
          new ParallelSubAgentDelegator.Request("two","reviewer","two",null)),new ToolContext(Map.of(RunExecutionContext.KEY,execution)));
      assertThat(batch.status()).isEqualTo(ParallelSubAgentDelegator.Status.PARTIAL);
      assertThat(calls).hasValue(1);assertThat(budget.remainingLlmCalls()).isZero();
    }
  }
  @Test void sharedGoalReservationIsChargedOnceAndCannotBypassLocalLimit() {
    var charged=new AtomicInteger();
    var goal=new OutputLimitRunBudget.LlmCallReservation(){
      public boolean tryReserve(){charged.incrementAndGet();return true;}public int remaining(){return 10;}
    };
    var execution=context(new OutputLimitRunBudget(0,1,goal));
    var child=execution.sharedLlmReservation();
    assertThat(child.tryReserve()).isTrue();
    assertThat(child.tryReserve()).isFalse();
    assertThat(child.remaining()).isZero();
    assertThat(charged).hasValue(1);
  }
  @Test void independentSemanticJudgeAlsoConsumesNormalChatBudget() throws Exception {
    for(int limit:List.of(1,2)) {
      var fixture=fixture();fixture.evidenceValidation=true;fixture.maxSteps=4;
      fixture.requiredCallsConfiguration="semanticValidation: true\n";
      var calls=new AtomicInteger();
      var child=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(
          prompt.getInstructions().getFirst().getText().contains("independent semantic validator")
              ?"{\"valid\":true,\"issues\":[]}"
              :"{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}"));},"2s");
      var budget=new OutputLimitRunBudget(0,limit);var execution=context(budget);
      var result=new SubAgentTools(child,null).delegateTask("reviewer","inspect",null,
          new ToolContext(Map.of(RunExecutionContext.KEY,execution)));
      assertThat(result.status()).isEqualTo(limit==1?SubAgentResult.Status.FAILED:SubAgentResult.Status.COMPLETED);
      assertThat(calls).hasValue(limit);assertThat(budget.remainingLlmCalls()).isZero();
    }
  }
  @Test void cancelledParentStopsChildBeforeModelAndDoesNotConsumeBudget() throws Exception {
    var fixture=fixture();var calls=new AtomicInteger();
    var child=fixture.runner(prompt->{calls.incrementAndGet();return Flux.just(fixture.answer(SubAgentResultParserTest.VALID));},"2s");
    var budget=new OutputLimitRunBudget(0,1);var execution=context(budget);execution.cancel();
    var result=new SubAgentTools(child,null).delegateTask("reviewer","inspect",null,
        new ToolContext(Map.of(RunExecutionContext.KEY,execution)));
    assertThat(result.status()).isEqualTo(SubAgentResult.Status.CANCELLED);
    assertThat(calls).hasValue(0);assertThat(budget.remainingLlmCalls()).isEqualTo(1);
  }
}
