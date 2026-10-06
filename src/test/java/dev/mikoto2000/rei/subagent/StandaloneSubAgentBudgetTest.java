package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class StandaloneSubAgentBudgetTest {
  @TempDir Path directory;
  final SubAgentToolPolicy policy=new SubAgentToolPolicy(Set.of("readMultiFile"));
  final CommandCancellationService cancellation=new CommandCancellationService();
  SubAgentRegistry registry;
  SubAgentRunner runner(java.util.function.Function<Prompt,Flux<ChatResponse>> responses,String extra) throws Exception {
    Files.writeString(directory.resolve("reviewer.yaml"),SubAgentConfigurationTest.yaml("reviewer")+extra);
    registry=new SubAgentRegistry(directory,new SubAgentDefinitionLoader(policy,m->true));registry.reload();
    var model=new ChatModel() {
      public ChatResponse call(Prompt prompt){throw new AssertionError("streaming required");}
      public Flux<ChatResponse> stream(Prompt prompt){return responses.apply(prompt);}
    };
    var tool=new ToolCallback() {
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("readMultiFile").description("read").inputSchema("{}").build();}
      public String call(String input){return "observed";}
    };
    return new SubAgentRunner(registry,policy,m->model,m->ToolCallingChatOptions.builder().build(),()->List.of(tool),
        cancellation,new AgentEventFactory(Clock.systemUTC()),e->{},Clock.systemUTC());
  }
  ChatResponse answer(String content,Integer tokens) {
    var metadata=tokens==null?ChatResponseMetadata.builder().build():ChatResponseMetadata.builder().usage(new DefaultUsage(tokens,0)).build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(content))),metadata);
  }
  SubAgentProperties limits(int calls,long tokens) {
    var properties=new SubAgentProperties();properties.setStandaloneMaxLlmCalls(calls);properties.setStandaloneMaxTotalTokens(tokens);return properties;
  }
  List<ParallelSubAgentDelegator.Request> requests(int count) {
    return java.util.stream.IntStream.range(0,count).mapToObj(i->new ParallelSubAgentDelegator.Request("item-"+i,"reviewer","task",null)).toList();
  }
  @Test void directUnknownUsageCannotCompleteButEachInvocationHasItsOwnBudget() throws Exception {
    var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID,null));},"");
    runner.setStandaloneBudgetProperties(limits(2,5));
    for(int i=0;i<2;i++) {
      var result=runner.run("reviewer","task",null);
      assertEquals(SubAgentResult.Status.FAILED,result.status());assertTrue(result.output().contains("TOKEN_USAGE_UNKNOWN"));
    }
    assertEquals(2,calls.get());
  }
  @Test void parallelBatchSharesOneCallAllowanceAcrossAllChildren() throws Exception {
    var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID,2));},"");
    runner.setStandaloneBudgetProperties(limits(8,0));
    try(var delegator=new ParallelSubAgentDelegator(runner,registry,cancellation,Duration.ofSeconds(3))) {
      delegator.setStandaloneBudgetProperties(limits(1,0));
      var result=delegator.delegate(requests(4));
      assertEquals(ParallelSubAgentDelegator.Status.PARTIAL,result.status());
      assertEquals(1,result.items().stream().filter(i->i.status()==ParallelSubAgentDelegator.Status.COMPLETED).count());
      assertEquals(1,calls.get());
      assertEquals(List.of("item-0","item-1","item-2","item-3"),result.items().stream().map(ParallelSubAgentDelegator.Item::id).toList());
      assertEquals(ParallelSubAgentDelegator.Status.COMPLETED,delegator.delegate(requests(1)).status());
      assertEquals(2,calls.get());
    }
  }
  @Test void parallelInFlightUsageIsChargedAndLaterChildrenCannotBypassOvershoot() throws Exception {
    var calls=new AtomicInteger();var entered=new java.util.concurrent.CountDownLatch(2);
    var runner=runner(p->{calls.incrementAndGet();entered.countDown();try{assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));}
      catch(InterruptedException e){throw new java.util.concurrent.CancellationException();}
      return Flux.just(answer(SubAgentResultParserTest.VALID,3));},"");
    try(var delegator=new ParallelSubAgentDelegator(runner,registry,cancellation,Duration.ofSeconds(4))) {
      delegator.setStandaloneBudgetProperties(limits(10,5));
      var result=delegator.delegate(requests(4));assertEquals(2,calls.get());
      assertTrue(result.items().stream().anyMatch(i->i.result()!=null&&i.result().output().contains("TOKEN_BUDGET_EXCEEDED")));
      assertTrue(result.items().stream().noneMatch(i->i.status()==ParallelSubAgentDelegator.Status.COMPLETED&&i.id().matches("item-[23]")));
    }
  }
  @Test void repairAndIndependentSemanticJudgeShareTheSameStandaloneReservation() throws Exception {
    var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return Flux.just(answer("{}",2));},"maxRepairs: 1\n");
    runner.setStandaloneBudgetProperties(limits(1,0));
    var result=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.FAILED,result.status());
    assertEquals(1,calls.get());assertEquals(1,result.repairAttempts());assertFalse(result.validationHistory().isEmpty());
    calls.set(0);
    runner=runner(p->{calls.incrementAndGet();return Flux.just(answer(
        "{\"status\":\"PARTIAL\",\"summary\":\"unknown\",\"result\":{\"evidence\":[]},\"warnings\":[]}",5));},
        "evidenceTools: [readMultiFile]\nsemanticValidation: true\n");
    runner.setStandaloneBudgetProperties(limits(0,5));result=runner.run("reviewer","task",null);
    assertEquals(SubAgentResult.Status.FAILED,result.status());assertTrue(result.output().contains("TOKEN_BUDGET_EXCEEDED"));assertEquals(1,calls.get());
  }
  @Test void overshootStopsBeforeValidationAndExplicitParentReservationTakesPriority() throws Exception {
    var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID,6));},"");
    runner.setStandaloneBudgetProperties(limits(1,5));
    var failed=runner.run("reviewer","task",null);assertEquals(SubAgentResult.Status.FAILED,failed.status());
    assertNull(failed.structuredOutput());assertTrue(failed.output().contains("TOKEN_BUDGET_EXCEEDED"));
    var parent=new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation() {
      final AtomicInteger remaining=new AtomicInteger(1);
      public boolean tryReserve(){return remaining.getAndDecrement()>0;}
      public int remaining(){return remaining.get();}
    };
    assertEquals(SubAgentResult.Status.COMPLETED,runner.run("reviewer","task",null,parent).status());assertEquals(2,calls.get());
  }
  @Test void providerFailureAndUnknownUsageKeepSharedBatchClosed() throws Exception {
    for(boolean failed:List.of(false,true)) {
      var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return failed?Flux.error(new IllegalStateException("private provider detail")):
          Flux.just(answer(SubAgentResultParserTest.VALID,0));},"");
      try(var delegator=new ParallelSubAgentDelegator(runner,registry,cancellation,Duration.ofSeconds(3))) {
        delegator.setStandaloneBudgetProperties(limits(1,5));
        var result=delegator.delegate(requests(4));assertEquals(ParallelSubAgentDelegator.Status.FAILED,result.status());assertEquals(1,calls.get());
        assertTrue(result.items().stream().anyMatch(i->i.result()!=null&&i.result().output().contains("TOKEN_USAGE_UNKNOWN")));
        assertFalse(result.toString().contains("private provider detail"));
      }
    }
  }
  @Test void disabledSettingsAndInvalidPreflightPreserveLegacyBehavior() throws Exception {
    var calls=new AtomicInteger();var runner=runner(p->{calls.incrementAndGet();return Flux.just(answer(SubAgentResultParserTest.VALID,null));},"");
    assertEquals(SubAgentResult.Status.COMPLETED,runner.run("reviewer","task",null).status());
    runner.setStandaloneBudgetProperties(limits(1,1));
    assertEquals(SubAgentResult.Status.UNKNOWN_AGENT,runner.run("missing","task",null).status());assertEquals(1,calls.get());
    try(var delegator=new ParallelSubAgentDelegator(runner,registry,cancellation,Duration.ofSeconds(3))) {
      delegator.setStandaloneBudgetProperties(limits(1,1));assertThrows(IllegalArgumentException.class,()->delegator.delegate(requests(9)));
    }
    assertEquals(1,calls.get());
  }
  @Test void settingBindingAndAtomicReservationsRejectInvalidLimits() throws Exception {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.subagents.standalone-max-llm-calls","3","rei.subagents.standalone-max-total-tokens","5"));
    var properties=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.subagents",
        org.springframework.boot.context.properties.bind.Bindable.of(SubAgentProperties.class)).get();
    assertEquals(3,properties.getStandaloneMaxLlmCalls());assertEquals(5,properties.getStandaloneMaxTotalTokens());
    assertThrows(IllegalArgumentException.class,()->properties.setStandaloneMaxLlmCalls(1001));
    assertThrows(IllegalArgumentException.class,()->properties.setStandaloneMaxTotalTokens(-1));
    var budget=StandaloneSubAgentBudget.create(limits(3,5));
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var jobs=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<8;i++)jobs.add(pool.submit(budget::tryReserve));
      int accepted=0;for(var job:jobs)if(job.get())accepted++;assertEquals(3,accepted);
    }
    budget.recordTotalTokens(3);assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,()->budget.recordTotalTokens(3));
    assertEquals(0,budget.remaining());assertTrue(budget.tokenExhausted());
  }
  @Test void timedOutProviderLeavesSharedUsageUnknownBeforeNextReservation() throws Exception {
    var fixture=new SubAgentRunnerTest();fixture.directory=directory;
    var disposed=new java.util.concurrent.CountDownLatch(1);
    var runner=fixture.runner(p->Flux.<ChatResponse>never().doOnCancel(disposed::countDown),"100ms");
    var budget=StandaloneSubAgentBudget.create(limits(2,5));
    assertEquals(SubAgentResult.Status.TIMEOUT,runner.run("reviewer","task",null,budget).status());
    assertTrue(disposed.await(2,java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(budget.usageUnknown());assertEquals(0,budget.remaining());
    assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,budget::tryReserve);
  }
}
