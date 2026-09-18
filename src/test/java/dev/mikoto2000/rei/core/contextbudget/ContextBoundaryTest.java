package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import reactor.core.publisher.Flux;

class ContextBoundaryTest {
  @Test void assemblesAfterToolsCompleteAndKeepsRawPromptOutsideProjection() {
    List<String> order = new ArrayList<>();
    var events = new AgentEventFactory(Clock.systemUTC());
    var context = new RunExecutionContext("run", new OutputLimitRunBudget(2, 10),
        mock(ProgressEvaluator.class), events, e -> {});
    context.setRunContext(new AgentRunContext("run", "chat", Path.of(".")));
    when(context.evaluator().afterTool(any(), any(), any(), any())).thenReturn(List.of());
    var assembler = mock(ContextAssembler.class);
    when(assembler.assemble(any(), any(), any(), any())).thenAnswer(invocation -> {
      order.add("assemble");
      Prompt raw = invocation.getArgument(0);
      if (raw.getInstructions().getLast() instanceof ToolResponseMessage tool) {
        assertThat(tool.getResponses().getFirst().responseData()).isEqualTo("full raw result");
        return new Prompt(List.of(new UserMessage("compact representation")), raw.getOptions());
      }
      return raw;
    });
    var tool = new ToolCallback() {
      public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build(); }
      public String call(String input) { order.add("tool completed"); return "full raw result"; }
    };
    ChatModel model = new ChatModel() {
      int calls;
      public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt prompt) {
        order.add("llm");
        if (++calls == 1) return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
            .content("").toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "readFile", "{}"))).build()))));
        assertThat(prompt.getContents()).contains("compact representation");
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
      }
    };
    var options = ToolCallingChatOptions.builder().toolCallbacks(tool).toolContext(Map.of(RunExecutionContext.KEY, context)).build();
    new StagnationChatModel(model, assembler).stream(new Prompt(new UserMessage("request"), options)).blockLast();
    assertThat(order).containsExactly("assemble", "llm", "tool completed", "assemble", "llm");
  }
}
