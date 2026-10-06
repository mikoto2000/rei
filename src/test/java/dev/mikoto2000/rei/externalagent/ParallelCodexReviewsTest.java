package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

@Tag("integration")
class ParallelCodexReviewsTest {
 @TempDir Path root;
 RunExecutionContext run(String request,int calls){var run=new RunExecutionContext("run",new OutputLimitRunBudget(0,calls),null,null,null);run.setUserRequest(request);run.setRunContext(new AgentRunContext("run","chat",root,"p"));return run;}
 List<ExternalAgentDelegationService.ParallelRequest> requests(int count){return java.util.stream.IntStream.range(0,count).mapToObj(i->new ExternalAgentDelegationService.ParallelRequest("item-"+i,"review "+i,null,null)).toList();}
 ExternalAgentDelegationService service(ExternalAgentExecutor executor){var service=new ExternalAgentDelegationService(executor,new CommandCancellationService(),new AgentEventFactory(Clock.systemUTC()),event->{},Optional.empty());var props=new CodexProperties();props.setParallelReviewEnabled(true);service.modelBudgetProperties(props);return service;}
 ExternalAgentResult success(){return new ExternalAgentResult(ExternalAgentResult.Status.SUCCESS,"ok",List.of(),List.of(),1,0,"private raw");}
 @Test void independentReviewsReallyOverlapAndShareOneParentDelegationAndCallBudget() throws Exception {
  var entered=new CountDownLatch(2);var active=new AtomicInteger();var maximum=new AtomicInteger();var calls=new AtomicInteger();var run=run("Codex に複数の対象を並列レビューして",10);
  try(var service=service((request,cancelled)->{int current=active.incrementAndGet();maximum.accumulateAndGet(current,Math::max);calls.incrementAndGet();entered.countDown();try{assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(run.runContext(),AgentRunScope.current());assertEquals(ExternalAgentRequest.Action.REVIEW,request.action());return success();}catch(InterruptedException e){throw new CancellationException();}finally{active.decrementAndGet();}})) {
   var result=service.reviewParallel(run,requests(3));assertEquals(ExternalAgentDelegationService.ParallelStatus.COMPLETED,result.status());assertEquals(List.of("item-0","item-1","item-2"),result.items().stream().map(ExternalAgentDelegationService.ParallelItem::requestId).toList());assertEquals(2,maximum.get());assertEquals(3,calls.get());assertEquals(7,run.sharedLlmReservation().remaining());assertFalse(result.toString().contains("private raw"));assertEquals(ExternalAgentResult.Status.REJECTED,service.review(run,"again",null,null).status());assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(run,requests(2)).status());
  }
 }
 @Test void defaultsOrdinaryOrQuotedRequestsCannotAuthorizeMoreExternalCalls() {
  var calls=new AtomicInteger();try(var service=new ExternalAgentDelegationService((request,cancelled)->{calls.incrementAndGet();return success();},new CommandCancellationService(),new AgentEventFactory(Clock.systemUTC()),event->{},Optional.empty())){
   var run=run("Codex に並列レビューして",10);assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(run,requests(2)).status());assertFalse(run.externalDelegationUsed());var props=new CodexProperties();props.setParallelReviewEnabled(true);service.modelBudgetProperties(props);
   for(String request:List.of("Codex にレビューして","Codex にレビューして。対象名は「parallel」です","do not ask Codex to review in parallel","/agent codex review parallel","Codex にレビューして、並列にはしないで","please ask Codex to review, not in parallel")){var unauthorized=run(request,10);assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(unauthorized,requests(2)).status());assertFalse(unauthorized.externalDelegationUsed());}
  }assertEquals(0,calls.get());
 }
 @Test void exhaustedParentBudgetCannotStartAProviderEvenWhenLegacyInheritanceIsDisabled() {
  var calls=new AtomicInteger();try(var service=service((request,cancelled)->{calls.incrementAndGet();return success();})){var run=run("please ask Codex to review in parallel",0);assertThrows(ExecutionStoppedException.class,()->service.reviewParallel(run,requests(2)));assertEquals(0,calls.get());}
 }
 @Test void partialResultsKeepTheirIndependentPersistedOwnerAndTerminalEvents() {
  var events=new CopyOnWriteArrayList<AgentEvent>();var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("reviews.db"));var history=new ExternalReviewRepository(source,Clock.systemUTC());
  try(var service=new ExternalAgentDelegationService((request,cancelled)->request.task().contains("review 1")?new ExternalAgentResult(ExternalAgentResult.Status.FAILED,"failed",List.of(),List.of(),1,1,""):success(),new CommandCancellationService(),new AgentEventFactory(Clock.systemUTC()),events::add,Optional.empty())) {
   var props=new CodexProperties();props.setParallelReviewEnabled(true);service.modelBudgetProperties(props);service.reviewHistory(history);var run=run("Codex に並列レビューして",10);var result=service.reviewParallel(run,requests(3));assertEquals(ExternalAgentDelegationService.ParallelStatus.PARTIAL,result.status());assertEquals(3,history.list("p").size());
   for(var item:result.items()){assertNotNull(item.result().reviewId());var saved=history.get("p",item.result().reviewId());assertEquals("run",saved.runId());assertEquals("chat",saved.sessionId());assertEquals(root.toAbsolutePath().toString(),saved.projectRoot());assertEquals(item.result().status().name(),saved.status());}
   assertEquals(3,events.stream().filter(event->event.type()==AgentEventType.DELEGATION_STARTED).count());assertEquals(2,events.stream().filter(event->event.type()==AgentEventType.DELEGATION_COMPLETED).count());assertEquals(1,events.stream().filter(event->event.type()==AgentEventType.DELEGATION_FAILED).count());assertDoesNotThrow(run::checkActive);
  }
 }
 @Test void timeoutKeepsStartedReviewIdsWithoutPretendingTheirProviderOutcomeIsKnown() throws Exception {
  var history=new ExternalReviewRepository(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("timeout.db")),Clock.systemUTC());var entered=new CountDownLatch(2);var finished=new CountDownLatch(2);
  try(var service=service((request,cancelled)->{entered.countDown();while(!cancelled.getAsBoolean())java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{finished.countDown();}return new ExternalAgentResult(ExternalAgentResult.Status.CANCELLED,"cancelled",List.of(),List.of(),1,null,"");});var caller=Executors.newSingleThreadExecutor()) {
   service.reviewHistory(history);var props=new CodexProperties();props.setParallelReviewEnabled(true);props.setParallelReviewTimeout(Duration.ofMillis(500));service.modelBudgetProperties(props);var run=run("Codex に並列レビューして",10);var future=caller.submit(()->service.reviewParallel(run,requests(2)));assertTrue(entered.await(2,TimeUnit.SECONDS));var result=future.get(2,TimeUnit.SECONDS);assertEquals(ExternalAgentDelegationService.ParallelStatus.TIMEOUT,result.status());for(var item:result.items()){assertNotNull(item.result().reviewId());assertEquals("p",history.get("p",item.result().reviewId()).projectId());assertFalse(item.result().success());}assertTrue(finished.await(2,TimeUnit.SECONDS));assertFalse(run.isCancelled());
  }
 }
 @Test void invalidBatchRejectsAllBeforeClaimOrProviderExecution() throws Exception {
  java.nio.file.Files.writeString(root.resolve("inside.txt"),"safe");var calls=new AtomicInteger();
  try(var service=service((request,cancelled)->{calls.incrementAndGet();return success();})){
   for(var batch:List.of(requests(5),List.of(requests(1).getFirst(),requests(1).getFirst()),List.of(new ExternalAgentDelegationService.ParallelRequest("outside","review","../outside.txt",null)),List.of(new ExternalAgentDelegationService.ParallelRequest("missing","review","missing.txt",null)))){
    var run=run("Codex に並列レビューして",10);assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(run,batch).status());assertFalse(run.externalDelegationUsed());
   }
  }assertEquals(0,calls.get());
 }
 @Test void busyAdmissionDoesNotConsumeAnotherRunsDelegation() throws Exception {
  var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
  try(var service=service((request,cancelled)->{entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));return success();}catch(InterruptedException interrupted){throw new CancellationException();}});var caller=Executors.newSingleThreadExecutor()){
   var first=caller.submit(()->service.reviewParallel(run("Codex に並列レビューして",10),requests(2)));try{assertTrue(entered.await(2,TimeUnit.SECONDS));var second=run("Codex に並列レビューして",10);assertEquals(ExternalAgentDelegationService.ParallelStatus.BUSY,service.reviewParallel(second,requests(2)).status());assertFalse(second.externalDelegationUsed());}finally{release.countDown();}assertEquals(ExternalAgentDelegationService.ParallelStatus.COMPLETED,first.get(2,TimeUnit.SECONDS).status());
  }
 }
 @Test void budgetFailureInLaterItemInterruptsFirstWithoutWaitingForDeadline() throws Exception {
  var firstEntered=new CountDownLatch(1);var stopped=new CountDownLatch(1);
  try(var service=service((request,cancelled)->{if(request.task().contains("review 1")){try{assertTrue(firstEntered.await(2,TimeUnit.SECONDS));}catch(InterruptedException interrupted){throw new CancellationException();}throw new ExecutionStoppedException(ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED);}firstEntered.countDown();try{while(!cancelled.getAsBoolean())java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);throw new CancellationException();}finally{stopped.countDown();}});var caller=Executors.newSingleThreadExecutor()){
   var future=caller.submit(()->service.reviewParallel(run("Codex に並列レビューして",10),requests(2)));var error=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));assertInstanceOf(ExecutionStoppedException.class,error.getCause());assertTrue(stopped.await(2,TimeUnit.SECONDS));
  }
 }
 @Test void nativeCodexJsonUsageIsChargedForEachIndependentReview() throws Exception {
  var fixture=new CodexRunModelBudgetTest();fixture.root=root;var paid=new AtomicInteger();var budget=new OutputLimitRunBudget(0,10,null,100);var run=new RunExecutionContext("run",budget,null,null,null);run.setRunContext(new AgentRunContext("run","chat",root,"p"));run.setUserRequest("Codex に並列レビューして");
  try(var service=service(fixture.executor(fixture.output("{\"input_tokens\":3,\"output_tokens\":2}"),paid))){assertEquals(ExternalAgentDelegationService.ParallelStatus.COMPLETED,service.reviewParallel(run,requests(2)).status());assertEquals(2,paid.get());assertEquals(10,budget.totalTokens());assertEquals(8,budget.remainingLlmCalls());}
 }
 @Test void cancelledParentStopsOnlyItsRunningWorkAndNeverStartsQueuedItems() throws Exception {
  var entered=new CountDownLatch(2);var calls=new AtomicInteger();var run=run("Codex に並列レビューして",10);
  try(var service=service((request,cancelled)->{calls.incrementAndGet();entered.countDown();while(!cancelled.getAsBoolean())java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);throw new CancellationException();});var caller=Executors.newSingleThreadExecutor()){
   var future=caller.submit(()->service.reviewParallel(run,requests(4)));assertTrue(entered.await(2,TimeUnit.SECONDS));run.cancel();var error=assertThrows(ExecutionException.class,()->future.get(2,TimeUnit.SECONDS));assertInstanceOf(CancellationException.class,error.getCause());assertEquals(2,calls.get());assertTrue(run.isCancelled());
  }
 }
 @Test void unreportedInFlightUsageAtDeadlineStopsStrictParentBudget() throws Exception {
  var paid=new AtomicInteger();var executor=new ExternalAgentExecutor(){public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled){throw new AssertionError("budget must be supplied");}public ExternalAgentResult execute(ExternalAgentRequest request,java.util.function.BooleanSupplier cancelled,dev.mikoto2000.rei.llm.ModelCallBudget budget){budget.run();paid.incrementAndGet();while(!cancelled.getAsBoolean())java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);throw new CancellationException();}};
  var budget=new OutputLimitRunBudget(0,10,null,100);var run=new RunExecutionContext("run",budget,null,null,null);run.setRunContext(new AgentRunContext("run","chat",root,"p"));run.setUserRequest("Codex に並列レビューして");
  try(var service=service(executor)){var props=new CodexProperties();props.setParallelReviewEnabled(true);props.setParallelReviewTimeout(Duration.ofMillis(300));service.modelBudgetProperties(props);assertTrue(assertThrows(ExecutionStoppedException.class,()->service.reviewParallel(run,requests(2))).getMessage().contains("TOKEN_USAGE_UNKNOWN"));assertTrue(budget.usageUnknown());assertEquals(2,paid.get());assertThrows(ExecutionStoppedException.class,run::consumeNextLlmCall);}
 }
 @Test void semanticToolUsesActualRunAndExposesOnlyIndependentReviewInputs() {
  var service=org.mockito.Mockito.mock(ExternalAgentDelegationService.class);var tools=new ExternalAgentTools(service);var run=run("Codex に並列レビューして",10);var batch=requests(2);var context=new org.springframework.ai.chat.model.ToolContext(Map.of(RunExecutionContext.KEY,run));tools.requestParallelCodexReviews(batch,context);org.mockito.Mockito.verify(service).reviewParallel(run,batch);var callback=Arrays.stream(org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks()).filter(tool->tool.getToolDefinition().name().equals("requestParallelCodexReviews")).findFirst().orElseThrow();var schema=callback.getToolDefinition().inputSchema();assertTrue(schema.contains("requestId"));assertFalse(schema.contains("projectRoot"));assertFalse(schema.contains("externalSessionId"));assertTrue(callback.getToolDefinition().description().contains("explicitly"));
 }
 @Test void configurationBindsOptInAndBoundedDeadlineAndClosedServiceRejects() {
  var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("rei.external-agents.codex.parallel-review-enabled","true","rei.external-agents.codex.parallel-review-timeout","500ms"));var props=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.external-agents.codex",org.springframework.boot.context.properties.bind.Bindable.of(CodexProperties.class)).get();assertTrue(props.isParallelReviewEnabled());assertEquals(Duration.ofMillis(500),props.getParallelReviewTimeout());assertFalse(new CodexProperties().isParallelReviewEnabled());for(var invalid:List.of(Duration.ZERO,Duration.ofMillis(99),Duration.ofSeconds(121)))assertThrows(IllegalArgumentException.class,()->props.setParallelReviewTimeout(invalid));assertThrows(IllegalArgumentException.class,()->props.setParallelReviewTimeout(null));
  var service=service((request,cancelled)->{throw new AssertionError();});service.close();var run=run("Codex に並列レビューして",10);assertEquals(ExternalAgentDelegationService.ParallelStatus.REJECTED,service.reviewParallel(run,requests(2)).status());assertFalse(run.externalDelegationUsed());
 }
}
