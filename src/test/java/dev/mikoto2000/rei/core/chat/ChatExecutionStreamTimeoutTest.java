package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.StagnationChatModel;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.llm.FixedLlmChatClientProvider;
import dev.mikoto2000.rei.llm.FixedLlmModelProvider;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.RunAwareToolCallingAdvisor;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

class ChatExecutionStreamTimeoutTest {
  @Test
  void retryReplacesPartialUiResponseAndReturnsOnlySuccessfulText() {
    var calls = new AtomicInteger();
    var model =
        new ChatModel() {
          public ToolCallingChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
          }

          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          public Flux<ChatResponse> stream(Prompt prompt) {
            return calls.incrementAndGet() == 1
                ? Flux.concat(
                    Flux.just(text("discard this")), Flux.error(new SocketTimeoutException()))
                : Flux.just(text("successful answer"));
          }
        };
    var client =
        ChatClient.builder(new StagnationChatModel(model, null, 2))
            .defaultAdvisors(new RunAwareToolCallingAdvisor())
            .build();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var events = new ArrayList<AgentEvent>();
    var service =
        new ChatExecutionService(
            new FixedLlmChatClientProvider(client),
            holder,
            new FixedLlmModelProvider(),
            new LlmProperties(),
            new CommandCancellationService(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Clock.systemUTC(),
            Optional.empty(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    var result = service.execute("work");
    assertThat(result.success()).isTrue();
    assertThat(result.text()).isEqualTo("successful answer");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(events).noneMatch(e -> e.type() == AgentEventType.AGENT_RUN_FAILED);
    var completed =
        events.stream()
            .filter(e -> e.payload() instanceof MessageCompletedPayload)
            .map(e -> (MessageCompletedPayload) e.payload())
            .toList();
    assertThat(completed).hasSize(2);
    assertThat(completed.getFirst().text()).contains("再試行中").doesNotContain("discard");
    assertThat(completed.getLast().text()).isEqualTo("successful answer");
    assertThat(completed.getFirst().messageId()).isNotEqualTo(completed.getLast().messageId());
  }

  private static ChatResponse text(String value) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(value))));
  }
}
