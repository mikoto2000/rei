package dev.mikoto2000.rei.externalagent;

import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

class ClaudeCodeExtensionServiceTest {
  @TempDir Path root;
  ExternalReviewRepository history;org.springframework.jdbc.datasource.DriverManagerDataSource source;
  String nativeId=UUID.randomUUID().toString();
  String projectId=UUID.randomUUID().toString();
  @BeforeEach void setup()throws Exception {Files.writeString(root.resolve("A.txt"),"before\n");source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("history.db"));history=new ExternalReviewRepository(source,Clock.systemUTC());}
  RunExecutionContext run(String id,String input){var run=new RunExecutionContext(id,new dev.mikoto2000.rei.llm.OutputLimitRunBudget(0,10),null,null,null);run.setRunContext(new AgentRunContext(id,"session",root,projectId));run.setUserRequest(input);return run;}
  void parent(){var owner=new AgentRunContext("initial","session",root,projectId);history.start(owner,"parent",root,"A.txt",null,"claude");history.finish(projectId,"parent",new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"ok",List.of(),List.of(),0,0,"","parent",nativeId));}
  ExternalAgentDelegationService service(ExternalAgentExecutor executor){var service=new ExternalAgentDelegationService(executor,new dev.mikoto2000.rei.core.service.CommandCancellationService(),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{},Optional.empty());service.reviewHistory(history);return service;}
  @Test void continuationKeepsProviderAndConsumesItsParentEvenAfterFailure() {
    parent();var calls=new AtomicInteger();var executor=new ExternalAgentExecutor(){public boolean supportsContinuation(ExternalAgentRequest.Agent agent){return agent==ExternalAgentRequest.Agent.CLAUDE;}public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c){fail("Budget required");return null;}public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c,dev.mikoto2000.rei.llm.ModelCallBudget budget){assertEquals(ExternalAgentRequest.Agent.CLAUDE,r.agent());assertEquals(nativeId,r.externalSessionId());budget.run();calls.incrementAndGet();return new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"unavailable",List.of(),List.of(),0,1,"");}};
    try(var service=service(executor)) {
      var plain=run("plain","Claude Code にレビューして");assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(plain,ExternalAgentRequest.Agent.CLAUDE,"parent","review",null).status());assertFalse(plain.externalDelegationUsed());
      var first=service.continueReview(run("continue","Claude Code に続きのレビューを依頼して"),ExternalAgentRequest.Agent.CLAUDE,"parent","review",null);assertEquals(ExternalAgentResult.Status.FAILED,first.status());assertTrue(history.continuationAttempted("parent"));
      assertEquals(ExternalAgentResult.Status.REJECTED,service.continueReview(run("retry","Claude Code に続きのレビューを依頼して"),ExternalAgentRequest.Agent.CLAUDE,"parent","review",null).status());assertEquals(1,calls.get());assertEquals("claude",history.get(projectId,first.reviewId()).agent());
    }
  }
  @Test void fixProposalSavesAChangeSetWithoutApplyingAndDoesNotAuthorizeCodex() {
    parent();var calls=new AtomicInteger();var executor=new ExternalAgentExecutor(){public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c){throw new AssertionError();}public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c,dev.mikoto2000.rei.llm.ModelCallBudget budget){assertEquals(ExternalAgentRequest.Agent.CLAUDE,r.agent());assertEquals(ExternalAgentRequest.Action.PROPOSE_FIX,r.action());budget.run();calls.incrementAndGet();return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"proposal",List.of(),List.of(),0,0,"",null,null,new dev.mikoto2000.rei.core.TextChangeSetService.Request("A.txt","before\n","after\n"),null);}};
    try(var service=service(executor)) {
      service.changeSets(new dev.mikoto2000.rei.core.TextChangeSetService(new dev.mikoto2000.rei.core.TextChangeSetRepository(source)));
      var run=run("fix","Claude Code に修正案を依頼して");var result=service.proposeFix(run,ExternalAgentRequest.Agent.CLAUDE,"parent","fix",null);assertTrue(result.success(),result.summary());assertNotNull(result.changeSetId());assertNull(result.proposedChange());assertEquals(1,calls.get());
      try{assertEquals("before\n",Files.readString(root.resolve("A.txt")));}catch(Exception e){throw new RuntimeException(e);}
      assertFalse(ExternalAgentAuthorization.explicitFixProposalRequest(run.userRequest()));
    }
  }
  @Test void parallelClaudeUsesTheCommonBoundedBatchAndOneParentDelegation() {
    var calls=new AtomicInteger();var executor=new ExternalAgentExecutor(){public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c){throw new AssertionError();}public ExternalAgentResult execute(ExternalAgentRequest r,java.util.function.BooleanSupplier c,dev.mikoto2000.rei.llm.ModelCallBudget budget){assertEquals(ExternalAgentRequest.Agent.CLAUDE,r.agent());budget.run();calls.incrementAndGet();return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"ok",List.of(),List.of(),0,0,"");}};
    try(var service=service(executor)) {
      var properties=new ClaudeCodeProperties();properties.setEnabled(true);properties.setParallelReviewEnabled(true);service.claudeProperties(properties);
      var requests=List.of(new ExternalAgentDelegationService.ParallelRequest("one","review","A.txt",""),new ExternalAgentDelegationService.ParallelRequest("two","review","A.txt",""));
      var wrong=run("wrong","Codex に並列レビューして");assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(wrong,ExternalAgentRequest.Agent.CLAUDE,requests).status());assertFalse(wrong.externalDelegationUsed());
      var parent=run("batch","Claude Code に並列レビューして");var result=service.reviewParallel(parent,ExternalAgentRequest.Agent.CLAUDE,requests);assertEquals(ExternalAgentDelegationService.ParallelStatus.COMPLETED,result.status());assertEquals(2,result.items().size());assertEquals(2,calls.get());assertTrue(parent.externalDelegationUsed());assertEquals(2,history.list(projectId,"claude").size());
      assertEquals(ExternalAgentResult.Status.REJECTED,service.review(parent,ExternalAgentRequest.Agent.CLAUDE,"again","A.txt",null).status());assertEquals(2,calls.get());
    }
  }
}
