package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.execution.*;
import dev.mikoto2000.rei.core.project.ProjectContext;
import static org.assertj.core.api.Assertions.*;

class BackgroundExecutionRegistryTest {
  ProjectContext a=new ProjectContext(UUID.randomUUID().toString(),"A",Path.of("a"));
  @Test void commandExecutionsCoexistAndOnlyAgentAcceptsInterventions() {
    var tasks=new ArrayList<Runnable>();
    var router=new ConversationInputRouter(tasks::add,(c,p,q)->assertThat(q.drain()).containsExactly("guidance"));
    var summary=router.submitBackground(a,ExecutionType.SUMMARIZE,"url", e->{
      assertThat(ExecutionScope.current()).isEqualTo(e);
      assertThat(AgentRunScope.current()).isNull();
    });
    router.submitBackground(a,ExecutionType.IMAGE,"picture",e->{});
    assertThat(router.activeRuns()).isEmpty();
    assertThat(router.submit(a.root(),a.conversationId("chat:main"),"chat")).isEqualTo(ConversationInputRouter.Disposition.STARTED);
    assertThat(router.submit(a.root(),a.conversationId("chat:main"),"guidance")).isEqualTo(ConversationInputRouter.Disposition.QUEUED);
    assertThat(router.activeExecutions()).extracting(ActiveExecution::type).containsExactlyInAnyOrder(ExecutionType.AGENT,ExecutionType.SUMMARIZE,ExecutionType.IMAGE);
    assertThat(summary.projectId()).isEqualTo(a.id());
    tasks.forEach(Runnable::run);
    assertThat(router.activeExecutions()).isEmpty();
    assertThat(ExecutionScope.current()).isNull();
  }
  @Test void everyTerminalPathRemovesCommandsAndRestartIsEmpty() {
    for(var type:List.of(ExecutionType.SUMMARIZE,ExecutionType.IMAGE))
      for(RuntimeException failure:List.of(new IllegalStateException("failure"),new CancellationException(),new CompletionException(new TimeoutException()))) {
        var tasks=new ArrayList<Runnable>();
        var router=new ConversationInputRouter(tasks::add,(c,p,q)->{});
        router.submitBackground(a,type,"work",e->{throw failure;});
        assertThatThrownBy(()->tasks.getFirst().run()).isSameAs(failure);
        assertThat(router.activeExecutions()).isEmpty();
      }
    var rejected=new ConversationInputRouter(task->{throw new RejectedExecutionException();},(c,p,q)->{});
    assertThatThrownBy(()->rejected.submitBackground(a,ExecutionType.IMAGE,"work",e->{})).isInstanceOf(RejectedExecutionException.class);
    assertThat(rejected.activeExecutions()).isEmpty();
  }
  @Test void productionExecutorRunsCommandsConcurrently() throws Exception {
    var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
    try(var executor=new AgentRunConfiguration().agentRunExecutor()) {
      var router=new ConversationInputRouter(executor,(c,p,q)->{});
      try {
        for(var type:List.of(ExecutionType.SUMMARIZE,ExecutionType.IMAGE)) router.submitBackground(a,type,"work",e->{
          entered.countDown();
          try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
          catch(InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        assertThat(router.activeExecutions()).hasSize(2);
      } finally { release.countDown(); }
    }
  }
}
