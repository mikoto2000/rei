package dev.mikoto2000.rei.llm;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.timing.*;
import static dev.mikoto2000.rei.timing.TimingRecorder.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import reactor.core.publisher.Flux;
class AgentEventChatModelTimingTest {
 final AtomicLong tick=new AtomicLong();
 final TimingStore store=new TimingStore(true,10,20,100,Duration.ofHours(1),Clock.systemUTC(),tick::get);
 final AgentRunContext owner=new AgentRunContext("run","session",java.nio.file.Path.of("."),"project",AgentRunContext.RequestSource.SHELL);
 final Prompt prompt=new Prompt("PROMPT-SECRET",OpenAiChatOptions.builder().toolContext(Map.of(AgentRunContext.class.getName(),owner)).build());
 ChatResponse response(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
 AgentEventChatModel model(ChatModel delegate){store.beginRun("run","project","session");return new AgentEventChatModel(LlmFeature.CHAT,delegate,new AgentEventFactory(Clock.systemUTC()),new InMemoryAgentEventBus(),new TimingExecution(store));}
 @Test void emptyFirstChunkIsDistinctFromFirstTextAndDoesNotInventGeneratedTokenOrTps() {
  var delegate=mock(ChatModel.class);var empty=response("");var text=response("RESPONSE-SECRET");
  when(delegate.stream(prompt)).thenReturn(Flux.defer(()->{tick.set(4);return Flux.just(empty);}).concatWith(Flux.defer(()->{tick.set(9);return Flux.just(text);})));
  assertEquals(List.of(empty,text),model(delegate).stream(prompt).collectList().block());
  var run=store.latest("project","session").orElseThrow();var span=run.spans().getFirst();
  assertEquals(4,span.milestones().get(Metric.FIRST_FRAMEWORK_CHUNK));assertEquals(9,span.milestones().get(Metric.FIRST_GENERATION_TEXT));
  assertFalse(span.milestones().containsKey(Metric.FIRST_GENERATED_TOKEN));assertNull(span.generationTps());assertEquals(Status.SUCCESS,span.status());assertFalse(run.toString().contains("SECRET"));
 }
 @Test void knownUsageIsRecordedWithoutUsingTextLengthAsTokens() {
  var delegate=mock(ChatModel.class);var value=new ChatResponse(List.of(new Generation(new AssistantMessage("SECRET"))),ChatResponseMetadata.builder().usage(new DefaultUsage(2,3)).build());
  when(delegate.call(prompt)).thenReturn(value);assertSame(value,model(delegate).call(prompt));
  var span=store.latest("project","session").orElseThrow().spans().getFirst();assertEquals(2,span.inputTokens());assertEquals(3,span.outputTokens());assertNull(span.generatedTokens());
 }
 @Test void partialFailureAndUnfinishedCancellationDoNotBecomeSuccessful() {
  var delegate=mock(ChatModel.class);when(delegate.stream(prompt)).thenReturn(Flux.just(response("SECRET")).concatWith(Flux.error(new IllegalStateException("ERROR-SECRET"))));
  assertThrows(IllegalStateException.class,()->model(delegate).stream(prompt).blockLast());assertEquals(Status.FAILED,store.latest("project","session").orElseThrow().spans().getFirst().status());
 }
 @Test void retryGroupsPhysicalAttemptsAndPreservesExistingStepBudget() {
  var delegate=mock(ChatModel.class);when(delegate.stream(prompt)).thenReturn(Flux.error(new com.openai.errors.OpenAIRetryableException("PRIVATE")),Flux.just(response("OK")));
  var retries=new dev.mikoto2000.rei.core.chat.BoundedToolLoop.ModelRetries(1);
  assertEquals("OK",new dev.mikoto2000.rei.core.chat.BoundedToolLoop().runWithHistory(model(delegate),prompt,new java.util.concurrent.atomic.AtomicInteger(2),owner,()->{},null,retries).block().output());
  var spans=store.latest("project","session").orElseThrow().spans();var llm=spans.stream().filter(s->s.category()==Category.LLM).toList();
  assertEquals(2,llm.size());assertEquals(llm.getFirst().requestId(),llm.getLast().requestId());assertNotEquals(llm.getFirst().attemptId(),llm.getLast().attemptId());assertEquals(1,spans.stream().filter(s->s.category()==Category.RETRY).count());assertEquals(1,retries.attempts());verify(delegate,times(2)).stream(prompt);
 }
 @Test void subscriberDisconnectionIsRecordedWithoutChangingCancellation() {
  var delegate=mock(ChatModel.class);when(delegate.stream(prompt)).thenReturn(Flux.never());var subscription=model(delegate).stream(prompt).subscribe();subscription.dispose();
  assertEquals(Status.DISCONNECTED,store.latest("project","session").orElseThrow().spans().getFirst().status());
 }
}
