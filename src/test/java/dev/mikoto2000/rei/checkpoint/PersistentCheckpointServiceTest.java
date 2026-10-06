package dev.mikoto2000.rei.checkpoint;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PersistentCheckpointServiceTest {
  @TempDir Path root;
  PersistentCheckpointRepository repository;
  InMemoryAgentEventBus bus=new InMemoryAgentEventBus();
  List<Runnable> queue=new ArrayList<>();List<String> started=new ArrayList<>();
  PersistentCheckpointService service;
  org.springframework.beans.factory.support.StaticListableBeanFactory factory;
  CheckpointProperties settings;
  AgentRunContext owner;
  @BeforeEach void setup() {
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));
    settings=new CheckpointProperties();repository=new PersistentCheckpointRepository(source,settings);
    factory=new StaticListableBeanFactory();factory.addBean("router",new ConversationInputRouter(queue::add,(context,prompt,interventions)->started.add(context.runId())));
    service=new PersistentCheckpointService(repository,new CheckpointReconciler(null),settings,bus,
        factory.getBeanProvider(ConversationInputRouter.class),factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunRegistry.class),
        factory.getBeanProvider(dev.mikoto2000.rei.application.run.RunService.class),factory.getBeanProvider(dev.mikoto2000.rei.core.actionplan.ActionPlan.class),
        factory.getBeanProvider(dev.mikoto2000.rei.core.working.WorkingSet.class),factory.getBeanProvider(dev.mikoto2000.rei.core.taskstate.TaskState.class),factory.getBeanProvider(dev.mikoto2000.rei.core.checkpoint.CheckpointStore.class));
    owner=new AgentRunContext("run","session",root,"A");service.start(owner,"original request");
  }
  @AfterEach void close(){service.close();}
  PersistentCheckpoint state(){return repository.list("A").getFirst();}
  @Test void inspectionNeverExecutesAndResumeCreatesNewRunWithLineageAndLease() {
    service.finish(owner,"CANCELLED");String task=state().taskId();
    service.list("A");service.inspect("A",task);assertTrue(queue.isEmpty());
    var resumed=service.resume("A",task,AgentRunContext.RequestSource.SHELL);
    assertNotEquals(owner.runId(),resumed.runId());assertEquals(owner.runId(),resumed.previousRunId());
    assertEquals("run",state().resumedFromRunId());assertEquals(resumed.checkpointRevision(),state().resumedFromRevision());
    assertThrows(IllegalStateException.class,()->service.resume("A",task,AgentRunContext.RequestSource.SHELL));
    queue.removeFirst().run();assertEquals(List.of(resumed.runId()),started);assertFalse(repository.leased("A",task));
  }
  @Test void sideEffectWithoutSavedResultRemainsUnknownAndCannotAutomaticallyRepeat() {
    var event=new AgentEventFactory(java.time.Clock.systemUTC()).toolStarted("call","deploy","deployment").withOwnership(owner);
    bus.publishBoundary(event);service.finish(owner,"CANCELLED");
    assertEquals(PersistentCheckpoint.OperationStatus.UNKNOWN,state().operations().getFirst().status());
    var resumed=service.resume("A",state().taskId(),AgentRunContext.RequestSource.SHELL);
    var context=new AgentRunContext(resumed.runId(),"session",root,"A");
    assertThrows(IllegalStateException.class,()->bus.publishBoundary(new AgentEventFactory(java.time.Clock.systemUTC()).toolStarted("retry","deploy","deploy").withOwnership(context)));
    bus.publishBoundary(new AgentEventFactory(java.time.Clock.systemUTC()).toolStarted("read","readFile","read").withOwnership(context));
    assertTrue(service.context(resumed.runId()).contains("Unknown operations MUST NOT be replayed"));
  }
  @Test void duplicateEventsAndResultBeforePlanUpdatePreserveConfirmedEvidence() {
    var factory=new AgentEventFactory(java.time.Clock.systemUTC());var started=factory.toolStarted("call","writeFile","write").withOwnership(owner);
    bus.publishBoundary(started);long revision=state().revision();bus.publishBoundary(started);assertEquals(revision,state().revision());
    bus.publishBoundary(factory.toolCompleted("call","writeFile",1,"wrote result").withOwnership(owner));
    service.finish(owner,"FAILED");assertEquals(PersistentCheckpoint.OperationStatus.SUCCEEDED,state().operations().getFirst().status());
    assertEquals("TOOL_CONFIRMED",state().evidence().getFirst().origin());assertEquals("FAILED",state().status());
  }
  @Test void queuedResumeCancellationReleasesExecutionRightWithoutRunning() {
    service.finish(owner,"CANCELLED");var resumed=service.resume("A",state().taskId(),AgentRunContext.RequestSource.WEB);
    var context=new AgentRunContext(resumed.runId(),"session",root,"A",AgentRunContext.RequestSource.WEB);
    bus.publish(new AgentEventFactory(java.time.Clock.systemUTC()).runCancelled(resumed.runId(),null).withOwnership(context));
    assertFalse(repository.leased("A",state().taskId()));assertEquals("CANCELLED",state().status());assertTrue(started.isEmpty());
  }
  @Test void minimumBudgetKeepsUnknownOperationsAndNextActionBeforeLargeEvidence() {
    settings.setContextCharacters(1024);
    bus.publishBoundary(new AgentEventFactory(java.time.Clock.systemUTC()).toolStarted("call","deploy","deploy").withOwnership(owner));
    service.finish(owner,"CANCELLED");var resumed=service.resume("A",state().taskId(),AgentRunContext.RequestSource.SHELL);
    assertTrue(service.context(resumed.runId()).contains("call"));assertTrue(service.context(resumed.runId()).contains("nextAction"));
    assertTrue(service.context(resumed.runId()).length()<=1024);
  }
  @Test void rawResultsStayOutsideSnapshotAndPreserveActualModelToolCallReference() {
    var raw=new dev.mikoto2000.rei.core.contextbudget.RawToolResultStore(root);service.setRawResults(raw);
    String body="large output ".repeat(100000);
    var message=org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("actual-model-call","readMultiFile",body))).build();
    service.preserveResults(owner,List.of(message));
    var ref=state().evidence().getFirst();assertEquals("RAW_RESULT_REFERENCE",ref.origin());assertEquals("actual-model-call",ref.toolCallId());
    assertEquals(body,raw.read("session",ref.summary()).rawResult());assertFalse(repository.encode(state()).contains("large output"));
  }
  @Test void resumedLlmCancellationSavesProgressReleasesLeaseAndInjectsReferenceSeparately() {
    service.finish(owner,"CANCELLED");var prompts=new ArrayList<org.springframework.ai.chat.prompt.Prompt>();
    var model=new org.springframework.ai.chat.model.ChatModel(){
      public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build(); }
      public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt){throw new UnsupportedOperationException();}
      public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(org.springframework.ai.chat.prompt.Prompt prompt){prompts.add(prompt);return reactor.core.publisher.Flux.error(new java.util.concurrent.CancellationException());}
    };
    var holder=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);org.mockito.Mockito.when(holder.get()).thenReturn("test");
    var client=org.springframework.ai.chat.client.ChatClient.builder(model).defaultAdvisors(new RunScopedAdvisor(new ResumeContextAdvisor(service))).build();
    var chat=new ChatExecutionService(client,holder,new dev.mikoto2000.rei.core.service.CommandCancellationService(),Optional.empty(),new AgentEventFactory(java.time.Clock.systemUTC()),bus);
    org.springframework.test.util.ReflectionTestUtils.setField(chat,"checkpoints",service);
    factory.addBean("router",new ConversationInputRouter(queue::add,(context,prompt,input)->chat.execute(context,prompt,input)));
    var resumed=service.resume("A",state().taskId(),AgentRunContext.RequestSource.SHELL);queue.removeFirst().run();
    assertEquals("CANCELLED",state().status());assertFalse(repository.leased("A",state().taskId()));
    assertEquals(1,prompts.size());assertTrue(prompts.getFirst().getSystemMessage().getText().contains("original request"));
    assertFalse(prompts.getFirst().getUserMessage().getText().contains("original request"));assertNotEquals("run",resumed.runId());
  }
  @Test void crashAfterSideEffectBeforeResultPersistenceCannotRepeat() throws Exception {
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    var callback=new org.springframework.ai.tool.ToolCallback(){
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return org.springframework.ai.tool.definition.ToolDefinition.builder().name("deploy").description("deploy").inputSchema("{}").build();}
      public String call(String input){calls.incrementAndGet();return "published";}
    };
    // Reject the third revision: initial, STARTED, then result. The side effect has already happened.
    try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state.db"));var s=c.createStatement()) {
      s.execute("CREATE TRIGGER inject_result_failure BEFORE INSERT ON checkpoint_revisions WHEN NEW.revision=3 BEGIN SELECT RAISE(ABORT,'crash after side effect'); END");
    }
    try(var scope=AgentRunScope.open(owner)) {
      assertThrows(RuntimeException.class,()->new ToolEventCallbackDecorator(callback,new AgentEventFactory(java.time.Clock.systemUTC()),bus).call("{}"));
    }
    assertEquals(1,calls.get());assertEquals(PersistentCheckpoint.OperationStatus.STARTED,state().operations().getFirst().status());
  }
  @Test void cancellationAtDurablePreToolBoundaryMustNotStartTheSideEffect() {
    var calls=new java.util.concurrent.atomic.AtomicInteger();var events=new AgentEventFactory(java.time.Clock.systemUTC());
    var execution=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("run",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,5),new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(root),events,bus);
    execution.setRunContext(owner);
    var cancellation=bus.subscribe(event->{if(event.type()==AgentEventType.TOOL_STARTED)execution.cancel();});
    var callback=new org.springframework.ai.tool.ToolCallback(){
      public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition(){return org.springframework.ai.tool.definition.ToolDefinition.builder().name("deploy").description("deploy").inputSchema("{}").build();}
      public String call(String input){calls.incrementAndGet();return "published";}
    };
    var context=new org.springframework.ai.chat.model.ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,execution,AgentRunContext.class.getName(),owner));
    try {assertThrows(java.util.concurrent.CancellationException.class,()->new ToolEventCallbackDecorator(callback,events,bus).call("{}",context));assertEquals(0,calls.get());}
    finally {cancellation.unsubscribe();service.finish(owner,"CANCELLED");}
  }
}
