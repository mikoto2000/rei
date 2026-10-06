package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import reactor.core.publisher.Flux;

@Tag("integration")
class SleepModelBudgetTest {
  @TempDir Path dir;
  MemoryRepository repository;
  ChatModel model;
  ConversationTurnStore turns;
  SleepService service(int calls,long tokens,Integer extractionUsage,Integer resolutionUsage,boolean existing) {
    return service(calls,tokens,extractionUsage,resolutionUsage,existing,0,0);
  }
  SleepService service(int calls,long tokens,Integer extractionUsage,Integer resolutionUsage,boolean existing,long projectCalls,long projectTokens) {
    var props=new MemoryProperties(true,20,80,10,3,2000,60,null,null,
        new MemoryProperties.Sleep(.70,.50,50,12000,120,calls,tokens,projectCalls,projectTokens));
    var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("memory.db"));
    repository=new MemoryRepository(source,new MemoryService(source,props));
    if(existing)repository.insert(LongTermMemoryTest.candidate("Previous unrelated note",MemoryScope.PROJECT),"p","s");
    turns=ConversationTurnStore.inMemory();var owner=new AgentRunContext("turn1","s",dir,"p");
    turns.start(owner,"Use vision first");turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"agreed");
    model=mock(ChatModel.class);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(MemoryOutputTest.VALID.replace("\"t\"","\"turn1\""),extractionUsage)),
        Flux.just(response("{\"action\":\"NEW\",\"targetIds\":[]}",resolutionUsage)));
    var provider=mock(LlmModelProvider.class);when(provider.memoryChatModel()).thenReturn(model);
    when(provider.chatOptions(eq(LlmFeature.MEMORY),any())).thenAnswer(invocation->OpenAiChatOptions.builder().build());
    var processor=new LlmMemoryProcessor(provider,props);
    return new SleepService(repository,turns,processor,new MemoryResolver(processor,props),props);
  }
  ChatResponse response(String text,Integer tokens) {
    var metadata=ChatResponseMetadata.builder();if(tokens!=null)metadata.usage(new DefaultUsage(1,tokens-1));
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),metadata.build());
  }
  @Test void extractionOvershootCannotWriteMemoryOrAdvanceCheckpoint() {
    var sleep=service(10,3,4,1,false);
    var stopped=assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false));
    assertTrue(stopped.getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0,repository.lastProcessed("s"));assertTrue(repository.list("p",10,0).isEmpty());
    assertEquals("FAILED",repository.history("p",10).getFirst().status());
  }
  @Test void extractionAndResolutionShareCallLimitAndPreserveExistingMemory() {
    var sleep=service(1,0,2,2,true);
    var stopped=assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false));
    assertTrue(stopped.getMessage().contains("LLM_CALL_BUDGET_EXCEEDED"));
    verify(model,times(1)).stream(any(Prompt.class));
    assertEquals(0,repository.lastProcessed("s"));assertEquals(1,repository.list("p",10,0).size());
  }
  @Test void resolutionOvershootCannotCommitPartialPlans() {
    var sleep=service(10,3,2,2,true);
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    verify(model,times(2)).stream(any(Prompt.class));
    assertEquals(0,repository.lastProcessed("s"));assertEquals(1,repository.list("p",10,0).size());
  }
  @Test void exactLimitCanCommitWithoutAnotherModelCall() {
    var sleep=service(10,3,3,1,false);
    var text=MemoryOutputTest.VALID.replace("\"t\"","\"turn1\"");int middle=text.length()/2;
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(text.substring(0,middle),null),response(text.substring(middle),3)));
    assertEquals("COMPLETED",sleep.sleep("s","p",false).run().status());
    assertEquals(1,repository.lastProcessed("s"));assertEquals(1,repository.list("p",10,0).size());
    verify(model,times(1)).stream(any(Prompt.class));
  }
  @Test void unknownUsageStopsAndDisabledBudgetPreservesLegacyBehavior() {
    var sleep=service(10,3,null,null,false);
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0,repository.lastProcessed("s"));
    var legacy=service(0,0,null,null,false);
    assertEquals("COMPLETED",legacy.sleep("s","p",false).run().status());
  }
  @Test void providerFailureDoesNotBecomeFreeRetryWithinSleep() {
    var sleep=service(10,3,2,2,false);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("offline")));
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    assertEquals(0,repository.lastProcessed("s"));assertTrue(repository.list("p",10,0).isEmpty());
    verify(model,times(1)).stream(any(Prompt.class));
  }
  @Test void previewUsesSameBudgetAndDoesNotWriteFailureAudit() {
    var sleep=service(10,3,4,1,false);
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",true)).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0,repository.lastProcessed("s"));assertTrue(repository.history("p",10).isEmpty());
  }
  @Test void exactDuplicateDoesNotSpendResolutionCallAndNextInvocationGetsOwnBudget() {
    var sleep=service(1,0,null,null,false);sleep.sleep("s","p",false);
    var owner=new AgentRunContext("turn2","s",dir,"p");turns.start(owner,"same lesson");turns.finish(owner,ConversationTurnStore.Status.COMPLETED,"agreed");
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(MemoryOutputTest.VALID.replace("\"t\"","\"turn2\""),null)));
    assertEquals("COMPLETED",sleep.sleep("s","p",false).run().status());
    assertEquals(2,repository.lastProcessed("s"));assertEquals(1,repository.list("p",10,0).size());
    verify(model,times(2)).stream(any(Prompt.class));
  }
  @Test void invalidLimitsAreRejectedAndLegacyProcessorCannotBypassTokenCap() {
    assertThrows(IllegalArgumentException.class,()->new MemoryProperties.Sleep(.70,.50,50,12000,120,-1,0));
    assertThrows(IllegalArgumentException.class,()->new MemoryProperties.Sleep(.70,.50,50,12000,120,1001,0));
    assertThrows(IllegalArgumentException.class,()->new MemoryProperties.Sleep(.70,.50,50,12000,120,0,-1));
    assertEquals(0,new MemoryProperties.Sleep(.70,.50,50,12000,120).maxLlmCalls());
    MemoryCandidateExtractor legacy=batch->{fail("Unaccounted extractor must not run");return List.of();};
    var budget=new SleepModelBudget(new MemoryProperties.Sleep(.70,.50,50,12000,120,0,3),()->{});
    assertThrows(IllegalStateException.class,()->legacy.extract(List.of(),budget));
  }
  @Test void configurationBindingKeepsSleepDefaultsAndBindsPositiveLimits() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.memory.sleep.max-llm-calls","2","rei.memory.sleep.max-total-tokens","3000"));
    var properties=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.memory",
        org.springframework.boot.context.properties.bind.Bindable.of(MemoryProperties.class)).get();
    assertEquals(2,properties.sleep().maxLlmCalls());assertEquals(3000,properties.sleep().maxTotalTokens());
    assertEquals(.70,properties.sleep().minConfidence());assertEquals(.50,properties.sleep().minImportance());
    assertEquals(50,properties.sleep().maxTurns());assertEquals(12000,properties.sleep().maxInputTokens());
  }
  @Test void previewChargesProjectBudgetAndNewServiceCannotRetryForFree() {
    var sleep=service(0,0,2,2,false,1,0);
    assertEquals("PREVIEW",sleep.sleep("s","p",true).run().status());
    assertTrue(repository.history("p",10).isEmpty());assertEquals(0,repository.lastProcessed("s"));
    var restarted=service(0,0,2,2,false,1,0);
    assertTrue(assertThrows(RuntimeException.class,()->restarted.sleep("s","p",false)).getMessage().contains("LLM_CALL_BUDGET_EXCEEDED"));
    verify(model,never()).stream(any(Prompt.class));assertTrue(repository.list("p",10,0).isEmpty());
  }
  @Test void lifetimeTokensChargeFailedPlanAndBlockProviderAfterRestart() {
    var sleep=service(0,0,2,2,true,0,3);
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    assertEquals(0,repository.lastProcessed("s"));assertEquals(1,repository.list("p",10,0).size());
    var restarted=service(0,0,2,2,false,0,3);
    assertTrue(assertThrows(RuntimeException.class,()->restarted.sleep("s","p",false)).getMessage().contains("TOKEN_BUDGET_EXCEEDED"));
    verify(model,never()).stream(any(Prompt.class));
  }
  @Test void lifetimeUnknownUsageAndProviderFailureBlockLaterInvocations() {
    var sleep=service(0,0,2,2,false,0,10);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("offline")));
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    assertTrue(assertThrows(RuntimeException.class,()->sleep.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    verify(model,times(1)).stream(any(Prompt.class));
    var restarted=service(0,0,2,2,false,0,10);
    assertTrue(assertThrows(RuntimeException.class,()->restarted.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    verify(model,never()).stream(any(Prompt.class));
  }
  @Test void cancellationLeavesDurablePendingReservationWithoutAdvancingMemory() {
    var sleep=service(0,0,2,2,false,0,10);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new java.util.concurrent.CancellationException()));
    assertThrows(java.util.concurrent.CancellationException.class,()->sleep.sleep("s","p",false));
    assertEquals(0,repository.lastProcessed("s"));
    var restarted=service(0,0,2,2,false,0,10);
    assertTrue(assertThrows(RuntimeException.class,()->restarted.sleep("s","p",false)).getMessage().contains("TOKEN_USAGE_UNKNOWN"));
    verify(model,never()).stream(any(Prompt.class));
  }
  @Test void lifetimeBudgetBindingAndValidation() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of(
        "rei.memory.sleep.max-llm-calls-per-project","12","rei.memory.sleep.max-total-tokens-per-project","3000"));
    var properties=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.memory",
        org.springframework.boot.context.properties.bind.Bindable.of(MemoryProperties.class)).get();
    assertEquals(12,properties.sleep().maxLlmCallsPerProject());assertEquals(3000,properties.sleep().maxTotalTokensPerProject());
    assertThrows(IllegalArgumentException.class,()->new MemoryProperties.Sleep(.70,.50,50,12000,120,0,0,-1,0));
    assertThrows(IllegalArgumentException.class,()->new MemoryProperties.Sleep(.70,.50,50,12000,120,0,0,0,-1));
  }
}
