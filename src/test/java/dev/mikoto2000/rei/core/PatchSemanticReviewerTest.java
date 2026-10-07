package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class PatchSemanticReviewerTest {
  @TempDir Path root;
  RunExecutionContext run(int calls){var run=new RunExecutionContext("run",new OutputLimitRunBudget(0,calls),null,null,event->{});run.setRunContext(new AgentRunContext("run","session",root,UUID.randomUUID().toString()));return run;}
  SemanticPatchReviewService.Input input(){return new SemanticPatchReviewService.Input("check required value",List.of(new SemanticPatchReviewService.Requirement("R1","required value",List.of("A.java"),List.of("ATest#value"))),"a".repeat(64),"untrusted diff",Map.of("A.java","untrusted source"),List.of());}
  String valid(){return "{\"status\":\"MATCH\",\"requirements\":{\"R1\":\"PASS\"},\"dimensions\":{\"requirements\":\"PASS\",\"extraChanges\":\"PASS\",\"security\":\"PASS\",\"tests\":\"PASS\",\"hygiene\":\"PASS\",\"compatibility\":\"PASS\"}}";}
  ChatModel model(AtomicInteger calls,String output){return new ChatModel(){public ChatResponse call(Prompt prompt){throw new AssertionError("stream only");}public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt){calls.incrementAndGet();var options=(OpenAiChatOptions)prompt.getOptions();assertTrue(options.getToolCallbacks().isEmpty());assertEquals("none",options.getToolChoice());assertEquals(Set.of(AgentRunContext.class.getName()),options.getToolContext().keySet());assertTrue(prompt.getInstructions().getFirst().getText().contains("untrusted"));return reactor.core.publisher.Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));}};}
  @Test void oneToolFreeCallConsumesParentBudgetAndExhaustionCannotInvokeAgain()throws Exception {
    var calls=new AtomicInteger();var reviewer=new PatchSemanticReviewer(()->model(calls,valid()));var run=run(1);long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
    assertEquals("MATCH",reviewer.judge(input(),run,deadline).status());assertEquals(0,run.sharedLlmReservation().remaining());assertEquals("UNKNOWN",reviewer.judge(input(),run,deadline).status());assertEquals(1,calls.get());
  }
  @Test void malformedDuplicateTrailingAndOversizedVerdictsAbstain()throws Exception {
    for(String output:List.of(valid()+" {}",valid().replace("\"status\":\"MATCH\"","\"status\":\"MATCH\",\"status\":\"FAIL\""),"x".repeat(4097),"{\"status\":\"MATCH\"}")){
      var calls=new AtomicInteger();assertEquals("UNKNOWN",new PatchSemanticReviewer(()->model(calls,output)).judge(input(),run(1),System.nanoTime()+Duration.ofSeconds(5).toNanos()).status());assertEquals(1,calls.get());
    }
  }
  @Test void ambientToolsAndCancellationDoNotReachModel()throws Exception {
    var calls=new AtomicInteger();var callback=org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    ChatModel unsafe=new ChatModel(){public ChatResponse call(Prompt prompt){fail("no model");return null;}public org.springframework.ai.chat.prompt.ChatOptions getOptions(){return OpenAiChatOptions.builder().toolCallbacks(List.of(callback)).build();}};
    assertThrows(IllegalArgumentException.class,()->new PatchSemanticReviewer(()->unsafe).judge(input(),run(1),System.nanoTime()+Duration.ofSeconds(5).toNanos()));
    Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->new PatchSemanticReviewer(()->model(calls,valid())).judge(input(),run(1),System.nanoTime()+Duration.ofSeconds(5).toNanos()));}finally{Thread.interrupted();}assertEquals(0,calls.get());
  }
}
