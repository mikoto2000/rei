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
  @Test void cancellationAfterCompletedEventDoesNotRewriteCompletedTurn() {
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))));
      }
    };
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var cancellation = new CommandCancellationService();
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    var service = new ChatExecutionService(ChatClient.builder(model).build(), holder, cancellation, Optional.empty(),
        new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC()), event -> {
          if (event.type() == dev.mikoto2000.rei.event.AgentEventType.AGENT_RUN_COMPLETED) cancellation.cancel();
        });
    service.setConversationTurnStore(turns);
    try {
      assertThat(service.execute("work").success()).isTrue();
      assertThat(turns.read("chat:main").getLast().status().name()).isEqualTo("COMPLETED");
    } finally { Thread.interrupted(); }
  }
  @Test void unrelatedTurnsKeepCancellationContextAndExplicitResumeCanUseOriginalRequest() {
    var requests = new ArrayList<Prompt>();
    var workingSet = new dev.mikoto2000.rei.core.working.WorkingSet();
    workingSet.recordRead(Path.of("network.md"));
    var memory = org.springframework.ai.chat.memory.MessageWindowChatMemory.builder().build();
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) {
        requests.add(p);
        return requests.size() == 1 ? Flux.error(new java.util.concurrent.CancellationException())
            : Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("response")))));
      }
    };
    var client = ChatClient.builder(model).defaultAdvisors(RunScopedAdvisor.wrap(List.of(
        org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor.builder(memory).build(),
        new dev.mikoto2000.rei.core.working.WorkingSetAdvisor(workingSet)))).build();
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var service = new ChatExecutionService(client, holder, new CommandCancellationService(), Optional.empty());
    service.setChatMemory(memory);
    var queue = new UserInterventionQueue(); queue.offer("discard this guidance");
    service.execute(new AgentRunContext("A", "chat:main", Path.of(".")), "write network.md", queue);
    for (String input : List.of("こんにちは", "今日もこんにちは", "さっきの作業を続けて")) {
      assertThat(service.execute(input).success()).isTrue();
      var prompt = requests.getLast();
      assertThat(prompt.getSystemMessage().getText()).contains("CANCELLED", "write network.md",
          "unless the user explicitly asks to resume");
      assertThat(prompt.getUserMessage().getText()).endsWith(input).contains("network.md");
      assertThat(prompt.getContents()).doesNotContain("discard this guidance");
    }
    assertThat(workingSet.getFiles()).hasSize(1);
  }
  @Test void cancelledRunDiscardsPendingGuidanceAndRecordsCancellation() {
    ChatModel model = new ChatModel() {
      public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
      public Flux<ChatResponse> stream(Prompt p) { return Flux.error(new java.util.concurrent.CancellationException()); }
    };
    var holder = mock(ModelHolderService.class); when(holder.get()).thenReturn("test");
    var service = new ChatExecutionService(ChatClient.builder(model).build(), holder,
        new CommandCancellationService(), Optional.empty());
    var memory = mock(ChatMemory.class);
    service.setChatMemory(memory);
    var queue = new UserInterventionQueue(); queue.offer("secret pending guidance");
    var result = service.execute(new AgentRunContext("run", "chat:main", Path.of(".")), "write article", queue);
    assertThat(result.status().name()).isEqualTo("CANCELLED");
    verify(memory, never()).add(anyString(), argThat((List<Message> messages) ->
        messages.stream().anyMatch(m -> m.getText().contains("secret pending guidance"))));
    assertThat(queue.drain()).isEmpty();
    assertThat(queue.offer("late")).isFalse();
  }
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
