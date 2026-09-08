package dev.mikoto2000.rei.core.chat;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.service.*;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatExecutionInterventionTest {
  @Test void cancellationStateIsClearedEvenWhenFinalHistoryWriteFails() {
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) { return Flux.error(new IllegalStateException("request failed")); }
    };
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var cancellation = spy(new CommandCancellationService());
    var service = new ChatExecutionService(ChatClient.builder(model).build(), holder, cancellation, Optional.empty());
    var memory = mock(ChatMemory.class);
    doThrow(new IllegalStateException("memory unavailable")).when(memory).add(anyString(), anyList());
    service.setChatMemory(memory);
    var queue = new UserInterventionQueue(); queue.offer("guidance");
    assertThatThrownBy(() -> service.execute(new AgentRunContext("run", "chat:main", Path.of(".")), "start", queue))
        .hasMessage("memory unavailable");
    verify(cancellation).clear();
  }
  @Test void lateGuidanceContinuesSameRunAndIsSavedAsUserMessage() {
    var queue = new UserInterventionQueue();
    List<Prompt> requests = new ArrayList<>();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        requests.add(p);
        if (requests.size() == 1) queue.offer("keep README");
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
      }
    };
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    var service = new ChatExecutionService(ChatClient.builder(model).build(), holder,
        new CommandCancellationService(), Optional.empty());
    var memory = mock(ChatMemory.class);
    service.setChatMemory(memory);
    assertThat(service.execute(new AgentRunContext("run", "chat:main", Path.of(".")), "start", queue).success()).isTrue();
    assertThat(requests).hasSize(2);
    verify(memory).add(eq("chat:main"), argThat((List<Message> messages) ->
        messages.size() == 1 && messages.getFirst() instanceof UserMessage
            && messages.getFirst().getText().equals("keep README")));
    assertThat(queue.offer("after completion")).isFalse();
  }
}
