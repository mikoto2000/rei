package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.*;
import reactor.core.publisher.Flux;

class ChatExecutionStagnationTest {
  @Test
  void recoveredOutputLimitIsNotReportedAsTerminalFailure() {
    AtomicInteger calls = new AtomicInteger();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        int n = calls.incrementAndGet();
        AssistantMessage output = n == 1 ? AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("read", "function", "readFile", "{}"))).build()
            : new AssistantMessage(n == 2 ? "partial" : "done");
        String reason = n == 1 ? "tool_calls" : n == 2 ? "length" : "stop";
        return Flux.just(new ChatResponse(List.of(new Generation(output,
            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason(reason).build())),
            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(0, n)).build()));
      }
    };
    ToolCallback read = new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build();
      }
      public String call(String input) { return "new information"; }
    };
    ChatClient client = ChatClient.builder(new StagnationChatModel(model)).defaultToolCallbacks(read).build();
    List<AgentEvent> events = new ArrayList<>();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var planner = mock(OutputLimitReplanner.class);
    var service = new ChatExecutionService(new FixedLlmChatClientProvider(client), holder,
        new FixedLlmModelProvider(), new LlmProperties(), new CommandCancellationService(), Optional.empty(),
        Optional.of(planner), Optional.empty(), Optional.empty(), Clock.systemUTC(), Optional.empty(),
        new AgentEventFactory(Clock.systemUTC()), events::add);
    var result = service.execute("work");
    assertThat(result.success()).isTrue();
    assertThat(result.text()).endsWith("done");
    assertThat(calls.get()).isEqualTo(3);
    verifyNoInteractions(planner);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED);
    var completed = events.stream().filter(e -> e.type() == AgentEventType.AGENT_RUN_COMPLETED).findFirst().orElseThrow();
    assertThat(((AgentRunCompletedPayload) completed.payload()).completionTokens()).isEqualTo(6L);
  }
  @Test
  void actualChatClientStopsAndPublishesTypedFailureAndResetsNextRun() {
    AtomicInteger calls = new AtomicInteger();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        int n = calls.incrementAndGet();
        if (n > 35) return Flux.error(new IllegalStateException("loop guard"));
        return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + n, "function", "readFile", "{}"))).build()))));
      }
    };
    ToolCallback read = new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder().name("readFile").description("read").inputSchema("{}").build();
      }
      public String call(String input) { return "unchanged"; }
    };
    ChatClient client = ChatClient.builder(new StagnationChatModel(model)).defaultToolCallbacks(read).build();
    List<AgentEvent> events = new ArrayList<>();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var service = new ChatExecutionService(new FixedLlmChatClientProvider(client), holder,
        new FixedLlmModelProvider(), new LlmProperties(), new CommandCancellationService(), Optional.empty(),
        Optional.empty(), Optional.empty(), Optional.empty(), Clock.systemUTC(), Optional.empty(),
        new AgentEventFactory(Clock.systemUTC()), events::add);
    var first = service.execute("work");
    assertThat(first.success()).isFalse();
    assertThat(first.errorMessage()).contains("STAGNATED");
    assertThat(calls.get()).isEqualTo(13);
    var failed = events.stream().filter(e -> e.type() == AgentEventType.AGENT_RUN_FAILED).findFirst().orElseThrow();
    assertThat(((AgentRunFailedPayload) failed.payload()).error().code()).isEqualTo("stagnated");
    service.execute("new run");
    assertThat(calls.get()).isEqualTo(26);
  }
}
