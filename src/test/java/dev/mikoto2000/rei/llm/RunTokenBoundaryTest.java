package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import reactor.core.publisher.Flux;

class RunTokenBoundaryTest {
  RunExecutionContext run(OutputLimitRunBudget budget){return new RunExecutionContext("run",budget,
      new ProgressEvaluator(Path.of("."),null),new AgentEventFactory(Clock.systemUTC()),new InMemoryAgentEventBus());}
  ChatResponse response(int tokens,List<AssistantMessage.ToolCall> calls){return new ChatResponse(List.of(new Generation(
      AssistantMessage.builder().content("answer").toolCalls(calls).build())),
      ChatResponseMetadata.builder().usage(new DefaultUsage(1,tokens-1)).build());}
  @Test void overLimitParentResponseCannotExecuteRequestedTool() {
    var count=new AtomicInteger();
    ToolCallback tool=new ToolCallback(){
      public ToolDefinition getToolDefinition(){return ToolDefinition.builder().name("readMultiFile").description("read").inputSchema("{}").build();}
      public String call(String input){count.incrementAndGet();return "read";}
    };
    var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(11,List.of(
        new AssistantMessage.ToolCall("call","function","readMultiFile","{}")))));
    var context=run(new OutputLimitRunBudget(0,10,null,10));context.consumeNextLlmCall();
    var prompt=new Prompt("read",ToolCallingChatOptions.builder().toolCallbacks(tool).toolContext(Map.of(RunExecutionContext.KEY,context)).build());
    assertThatThrownBy(()->new StagnationChatModel(model).stream(prompt).collectList().block()).hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    assertThat(count).hasValue(0);
  }
  @Test void unknownFailedChildUsageBlocksParentAndReportedChildUsageIsCharged() {
    for(boolean failed:List.of(false,true)) {
      var budget=new OutputLimitRunBudget(0,10,null,10);var context=run(budget);
      var model=mock(ChatModel.class);
      when(model.stream(any(Prompt.class))).thenReturn(failed?Flux.error(new IllegalStateException("private"))
          :Flux.just(response(7,List.of())));
      var loop=new BoundedToolLoop();var prompt=new Prompt("read",ToolCallingChatOptions.builder().build());
      var output=loop.runWithHistory(model,prompt,new AtomicInteger(2),new AgentRunContext("child","subagent:child",Path.of(".")),()->{},context.sharedLlmReservation());
      if(failed) {
        assertThatThrownBy(output::block).hasMessageContaining("TOKEN_USAGE_UNKNOWN");
        assertThat(context.sharedLlmReservation().tryReserve()).isFalse();
      } else {assertThat(output.block().output()).isEqualTo("answer");assertThat(budget.totalTokens()).isEqualTo(7);}
    }
  }
  @Test void prepaidCallCannotStartAfterHelperExhaustsTokens() {
    var model=mock(ChatModel.class);var context=run(new OutputLimitRunBudget(0,10,null,5));
    context.consumeNextLlmCall();context.recordTotalTokens(5);
    var prompt=new Prompt("read",ToolCallingChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY,context)).build());
    assertThatThrownBy(()->new StagnationChatModel(model).stream(prompt).collectList().block()).hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
    verifyNoInteractions(model);
  }
  @Test void actualChatBoundaryReportsTokenStopInsteadOfSuccess() {
    var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(11,List.of())));
    var holder=mock(dev.mikoto2000.rei.core.service.ModelHolderService.class);when(holder.get()).thenReturn("test");
    var properties=new LlmProperties();properties.getOutputLimit().setMaxTotalTokensPerRun(10);
    var client=org.springframework.ai.chat.client.ChatClient.builder(new StagnationChatModel(model)).build();
    var service=new ChatExecutionService(new FixedLlmChatClientProvider(client),holder,new FixedLlmModelProvider(),properties,
        new dev.mikoto2000.rei.core.service.CommandCancellationService(),Optional.empty(),Optional.empty());
    var result=service.execute("answer");
    assertThat(result.success()).isFalse();assertThat(result.errorMessage()).contains("token");
  }
}
