package dev.mikoto2000.rei.llm;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.timing.*;
import static org.junit.jupiter.api.Assertions.*;
import reactor.core.publisher.Flux;
class TimingOverheadProbeTest {
 final ChatResponse answer=new ChatResponse(List.of(new Generation(new AssistantMessage("fixed"))));
 final ChatModel delegate=new ChatModel(){public ChatResponse call(Prompt prompt){return answer;}public Flux<ChatResponse> stream(Prompt prompt){return Flux.just(answer);}};
 long batch(boolean enabled,int batch) {
  var recorder=new TimingStore(enabled,100,2000,10000,Duration.ofHours(1),Clock.systemUTC(),System::nanoTime);
  var owner=new AgentRunContext("batch-"+batch,"session",java.nio.file.Path.of("."),"project");recorder.beginRun(owner.runId(),owner.projectId(),owner.conversationId());
  var prompt=new Prompt("fixed",OpenAiChatOptions.builder().toolContext(Map.of(AgentRunContext.class.getName(),owner)).build());
  var events=new InMemoryAgentEventBus();var eventCount=new java.util.concurrent.atomic.AtomicInteger();events.subscribe(event->eventCount.incrementAndGet());
  var model=new AgentEventChatModel(LlmFeature.CHAT,delegate,new AgentEventFactory(Clock.systemUTC()),events,new TimingExecution(recorder));
  long start=System.nanoTime();for(int i=0;i<1000;i++)assertSame(answer,model.stream(prompt).blockLast());long elapsed=System.nanoTime()-start;
  assertEquals(3000,eventCount.get());assertEquals(enabled?1000:0,recorder.statistics().spans());return elapsed;
 }
 @Test void enabledAndDisabledPathsPreserveOutputAndEventCountAndReportMeasuredOverhead() {
  for(int i=0;i<3;i++){batch(false,-i-1);batch(true,-i-1);}
  long[] disabled=new long[7],enabled=new long[7];for(int i=0;i<7;i++){disabled[i]=batch(false,i);enabled[i]=batch(true,i);}
  Arrays.sort(disabled);Arrays.sort(enabled);
  System.out.printf(Locale.ROOT,"TIMING_OVERHEAD stream fixture median 7x1000: disabled=%.3f us/call enabled=%.3f us/call delta=%.3f us/call; synthetic local observation, not real model latency%n",disabled[3]/1_000_000d,enabled[3]/1_000_000d,(enabled[3]-disabled[3])/1_000_000d);
 }
}
