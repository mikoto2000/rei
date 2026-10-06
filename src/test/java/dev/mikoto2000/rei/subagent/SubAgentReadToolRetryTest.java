package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.dao.TransientDataAccessResourceException;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;

@Tag("integration")
class SubAgentReadToolRetryTest {
  @TempDir Path dir;
  SubAgentRunnerTest fixture(){var f=new SubAgentRunnerTest();f.directory=dir;return f;}
  ChatResponse tool(){return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("call","function","readMultiFile","{}"))).build())));}
  ToolCallback callback(java.util.function.Supplier<String> action){return new ToolCallback(){public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("readMultiFile").description("fixture").inputSchema("{}").build();}public String call(String input){return action.get();}};}
  void configure(SubAgentRunner runner,SubAgentRunnerTest fixture,int retries,Set<ActionCapability> classified){var props=new SubAgentProperties();props.setMaxTransientReadToolRetries(retries);runner.setStandaloneBudgetProperties(props);var policy=new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(ActionCapability.READ,ActionCapability.LOCAL_WRITE),null,Map.of("readMultiFile",classified)));runner.setToolPermissionGuard(new ToolPermissionGuard(policy,new AgentEventFactory(Clock.systemUTC()),fixture.events::add));}
  @Test void optedInAutomaticallyApprovedReadRetriesOnlyTheToolAndKeepsFailureFacts() throws Exception {
    var f=fixture();var reads=new AtomicInteger();var models=new AtomicInteger();var cb=callback(()->{if(reads.incrementAndGet()==1)throw new TransientDataAccessResourceException("private database detail");return "saved evidence";});var runner=f.runner(prompt->Flux.just(models.incrementAndGet()==1?tool():f.answer(SubAgentResultParserTest.VALID)),"2s",()->List.of(cb));configure(runner,f,1,Set.of(ActionCapability.READ));var result=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.COMPLETED,result.status());assertEquals(2,reads.get());assertEquals(2,models.get());assertEquals(1,result.toolRetryAttempts());assertEquals(List.of("TRANSIENT_READ_TOOL_FAILURE"),result.toolRetryHistory());assertFalse(result.toString().contains("private database detail"));assertEquals(1,f.events.stream().filter(e->e.type()==AgentEventType.TOOL_FAILED).count());
  }
  @Test void defaultsWriteClassificationAndPermanentErrorsCannotRetry() throws Exception {
    for(int mode=0;mode<3;mode++) {var f=fixture();var reads=new AtomicInteger();var models=new AtomicInteger();final int value=mode;var runner=f.runner(prompt->Flux.just(models.incrementAndGet()==1?tool():f.answer(SubAgentResultParserTest.VALID)),"2s",()->List.of(callback(()->{reads.incrementAndGet();if(value==2)throw new IllegalArgumentException("permanent");throw new TransientDataAccessResourceException("temporary");})));configure(runner,f,mode==0?0:3,mode==1?Set.of(ActionCapability.LOCAL_WRITE):Set.of(ActionCapability.READ));var result=runner.run("reviewer","task",null);assertEquals(1,reads.get());assertEquals(0,result.toolRetryAttempts());}
  }
  @Test void retryLimitIsInvocationWideAndASecondToolFailureCannotResetIt() throws Exception {
    var f=fixture();f.maxSteps=3;var reads=new AtomicInteger();var models=new AtomicInteger();var runner=f.runner(prompt->Flux.just(models.incrementAndGet()<3?tool():f.answer(SubAgentResultParserTest.VALID)),"2s",()->List.of(callback(()->{int n=reads.incrementAndGet();if(n==1||n==3)throw new TransientDataAccessResourceException("temporary");return "read";})));configure(runner,f,1,Set.of(ActionCapability.READ));var result=runner.run("reviewer","task",null);assertEquals(3,reads.get());assertEquals(1,result.toolRetryAttempts());assertEquals(2,result.toolRetryHistory().size());
  }
  @Test void inheritedOneUseApprovalCannotBeReplayedAfterTransientFailure() throws Exception {
    var repository=new ToolApprovalRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approval.db")),Clock.systemUTC());
    var parent=new dev.mikoto2000.rei.core.chat.AgentRunContext("parent","session",dir,"project");
    var request=repository.request("readMultiFile","{}",parent);repository.decide("project",request.id(),true);
    var f=fixture();f.requiredCallsConfiguration="inheritApprovals: true\n";var reads=new AtomicInteger();
    var runner=f.runner(prompt->Flux.just(tool()),"2s",()->List.of(callback(()->{reads.incrementAndGet();throw new TransientDataAccessResourceException("temporary");})));
    var props=new SubAgentProperties();props.setMaxTransientReadToolRetries(3);runner.setStandaloneBudgetProperties(props);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(true,Set.of(),Set.of(),Map.of())),new AgentEventFactory(Clock.systemUTC()),f.events::add);guard.setApprovals(repository);runner.setToolPermissionGuard(guard);
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(parent)){var result=runner.run("reviewer","task",null);assertEquals(1,reads.get());assertEquals(0,result.toolRetryAttempts());}
    assertEquals("CONSUMED",repository.get("project",request.id()).status());
  }
  @Test void onlyTypedNetworkTimeoutsRetryAndReturnedErrorsAreNotExceptions() {
    for(var failure:List.<RuntimeException>of(new java.io.UncheckedIOException(new java.net.SocketTimeoutException()),new org.springframework.web.client.ResourceAccessException("fixture",new java.net.ConnectException()))) {
      var retries=new SubAgentReadToolRetries(1);var calls=new AtomicInteger();assertEquals("ok",retries.call(()->{if(calls.incrementAndGet()==1)throw failure;return "ok";},()->{},()->{},()->true));assertEquals(2,calls.get());assertEquals(1,retries.attempts());
    }
    var retries=new SubAgentReadToolRetries(3);assertThrows(java.io.UncheckedIOException.class,()->retries.call(()->{throw new java.io.UncheckedIOException(new java.io.IOException("timeout text"));},()->{},()->{},()->true));assertEquals(0,retries.attempts());assertEquals("timeout error",retries.call(()->"timeout error",()->{},()->{},()->true));assertEquals(0,retries.attempts());
  }
  @Test void policyAndCancellationAreRecheckedBeforeRetryCallbackStarts() {
    var retries=new SubAgentReadToolRetries(3);var calls=new AtomicInteger();var checks=new AtomicInteger();
    assertThrows(java.util.concurrent.CancellationException.class,()->retries.call(()->{calls.incrementAndGet();throw new TransientDataAccessResourceException("temporary");},()->{},()->{if(checks.incrementAndGet()==3)throw new java.util.concurrent.CancellationException();},()->true));assertEquals(1,calls.get());assertEquals(0,retries.attempts());
    var next=new SubAgentReadToolRetries(3);var eligibility=new AtomicInteger();assertThrows(IllegalStateException.class,()->next.call(()->{throw new TransientDataAccessResourceException("temporary");},()->{},()->{},()->eligibility.incrementAndGet()==1));assertEquals(0,next.attempts());
  }
  @Test void configurationBoundsAndLegacyResultRemainCompatible() {
    var props=new SubAgentProperties();assertEquals(0,props.getMaxTransientReadToolRetries());assertThrows(IllegalArgumentException.class,()->props.setMaxTransientReadToolRetries(4));assertThrows(IllegalArgumentException.class,()->props.setMaxTransientReadToolRetries(-1));
    var result=new SubAgentResult("a","r",SubAgentResult.Status.COMPLETED,"ok",java.time.Instant.EPOCH,java.time.Instant.EPOCH);assertEquals(0,result.toolRetryAttempts());assertTrue(result.toolRetryHistory().isEmpty());assertThrows(UnsupportedOperationException.class,()->result.toolRetryHistory().add("x"));
  }
  @Test void retriedReadEmbeddingSharesDurableGoalCallAllowance() throws Exception {
    var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("goal.db"));
    var goals=new dev.mikoto2000.rei.goal.GoalRepository(source,Clock.systemUTC());var parent=new dev.mikoto2000.rei.core.chat.AgentRunContext("parent","session",dir,"project");
    var goal=goals.create(parent,"Artifact","out.txt","a".repeat(64),3,2);var claim=goals.claim("project",goal.id());var runId=goals.beginAttempt(claim);
    var provider=org.mockito.Mockito.mock(org.springframework.ai.embedding.EmbeddingModel.class);
    org.mockito.Mockito.when(provider.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class))).thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(List.of(new org.springframework.ai.embedding.Embedding(new float[]{1,0},0))));
    var embedding=new dev.mikoto2000.rei.llm.BudgetedEmbeddingModel(provider);var reads=new AtomicInteger();var models=new AtomicInteger();var f=fixture();
    var runner=f.runner(prompt->{models.incrementAndGet();return Flux.just(tool());},"2s",()->List.of(callback(()->{reads.incrementAndGet();embedding.embed("query");throw new TransientDataAccessResourceException("temporary");})));configure(runner,f,1,Set.of(ActionCapability.READ));
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(parent)){var result=runner.run("reviewer","task",null,goals.modelBudget(claim,runId));assertEquals(SubAgentResult.Status.FAILED,result.status());assertTrue(result.output().contains("SHARED_LLM_BUDGET_EXHAUSTED"));assertEquals(1,result.toolRetryAttempts());}
    assertEquals(2,reads.get());assertEquals(1,models.get());org.mockito.Mockito.verify(provider,org.mockito.Mockito.times(1)).call(org.mockito.ArgumentMatchers.any(org.springframework.ai.embedding.EmbeddingRequest.class));assertEquals(2,goals.get("project",goal.id()).llmCallsUsed());assertNull(dev.mikoto2000.rei.llm.ModelCallBudgetScope.current());
  }
}
