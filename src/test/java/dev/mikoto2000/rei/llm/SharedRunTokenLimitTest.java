package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.stagnation.*;

class SharedRunTokenLimitTest {
  @Test void concurrentReportsPreserveEveryReportedToken() throws Exception {
    var budget=new OutputLimitRunBudget(0,10,null,80000);
    try(var executor=java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for(int worker=0;worker<8;worker++) {
        tasks.add(executor.submit(()->{for(int i=0;i<10000;i++)budget.recordTotalTokens(1);}));
      }
      for(var task:tasks)task.get();
    }
    assertThat(budget.totalTokens()).isEqualTo(80000);
    assertThat(budget.tryConsumeLlmCall()).isFalse();
  }
  @Test void parentAndChildrenShareReportedTokensAndStopBeforeNextCall() {
    var budget=new OutputLimitRunBudget(0,10,null,10);
    var run=new RunExecutionContext("run",budget,null,null,null);
    run.recordTotalTokens(6);
    var child=run.sharedLlmReservation();
    assertThat(child.tryReserve()).isTrue();child.recordTotalTokens(4);
    assertThat(child.tryReserve()).isFalse();
    assertThatThrownBy(run::consumeNextLlmCall).isInstanceOf(ExecutionStoppedException.class)
        .hasMessage("TOKEN_BUDGET_EXCEEDED");
    assertThat(budget.totalTokens()).isEqualTo(10);
  }
  @Test void unknownUsageAndOvershootFailClosedOnlyWhenEnabled() {
    for(Integer usage:new Integer[]{null,0,-1,11}) {
      var run=new RunExecutionContext("run",new OutputLimitRunBudget(0,10,null,10),null,null,null);
      assertThatThrownBy(()->run.recordTotalTokens(usage)).isInstanceOf(ExecutionStoppedException.class);
      assertThat(run.sharedLlmReservation().tryReserve()).isFalse();
    }
    var old=new OutputLimitRunBudget(0,10);
    assertThat(old.tokenLimitEnabled()).isFalse();
    assertThatCode(()->old.recordTotalTokens(null)).doesNotThrowAnyException();
  }
}
