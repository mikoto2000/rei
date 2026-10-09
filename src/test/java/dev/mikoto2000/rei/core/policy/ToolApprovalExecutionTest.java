package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;

@Tag("integration")
class ToolApprovalExecutionTest {
  @TempDir Path dir;
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void resumedChatExecutesExactApprovedCallbackOnce(boolean voice) {
    Clock clock=Clock.systemUTC();var events=new ArrayList<AgentEvent>();
    var repository=new ToolApprovalRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("approvals.db")),clock);
    var guard=new ToolPermissionGuard(new ToolPermissionPolicy(new ToolPermissionProperties(!voice,Set.of(),Set.of(),Map.of())),new AgentEventFactory(clock),events::add);
    guard.setApprovals(repository);
    var modelCalls=new AtomicInteger();var effects=new AtomicInteger();
    var delegate=new ChatModel() {
      public ChatResponse call(Prompt p){throw new UnsupportedOperationException();}
      public Flux<ChatResponse> stream(Prompt p){
        var message=modelCalls.getAndIncrement()==0?AssistantMessage.builder().content("").toolCalls(List.of(
            new AssistantMessage.ToolCall("call","function","writeMultiFile","{}"))).build():new AssistantMessage("done");
        return Flux.just(new ChatResponse(List.of(new Generation(message))));
      }
    };
    ToolCallback callback=new ToolCallback() {
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("writeMultiFile").description("write").inputSchema("{}").build();}
      public String call(String input){effects.incrementAndGet();return "saved";}
    };
    var model=new StagnationChatModel(delegate);
    for(int attempt=0;attempt<3;attempt++) {
      modelCalls.set(0);
      var budget=new OutputLimitRunBudget(2,5);budget.tryConsumeLlmCall();
      var context=new RunExecutionContext("run"+attempt,budget,new ProgressEvaluator(dir),new AgentEventFactory(clock),events::add);
      var owner=new AgentRunContext("run"+attempt,"session",dir,"project");context.setRunContext(voice?owner.asVoiceInput():owner);context.setToolPermissionGuard(guard);
      var prompt=new Prompt("write",ToolCallingChatOptions.builder().toolCallbacks(callback).toolContext(Map.of(RunExecutionContext.KEY,context)).build());
      if(attempt==1)assertDoesNotThrow(()->model.stream(prompt).blockLast());
      else assertThrows(RuntimeException.class,()->model.stream(prompt).blockLast());
      if(attempt==0){assertEquals(0,effects.get());repository.decide("project",repository.list("project").getFirst().id(),true);}
    }
    assertEquals(1,effects.get());
  }
}
