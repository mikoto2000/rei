package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.checkpoint.CheckpointException;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ChatExecutionCheckpointCapacityTest {
  private final AgentRunContext owner =
      new AgentRunContext("run", "session", Path.of("."), "project");
  private final List<AgentEvent> events = new ArrayList<>();
  private final AtomicInteger modelCalls = new AtomicInteger();
  private final PersistentCheckpointService checkpoints = mock(PersistentCheckpointService.class);
  private final ConversationTurnStore turns = ConversationTurnStore.inMemory();

  private ChatExecutionService service(Flux<ChatResponse> stream) {
    ChatModel model =
        new ChatModel() {
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          public Flux<ChatResponse> stream(Prompt prompt) {
            modelCalls.incrementAndGet();
            return stream;
          }
        };
    var models = mock(ModelHolderService.class);
    when(models.get()).thenReturn("test");
    var service =
        new ChatExecutionService(
            ChatClient.builder(model).build(),
            models,
            new CommandCancellationService(),
            Optional.empty(),
            new AgentEventFactory(Clock.systemUTC()),
            events::add);
    service.setCheckpoints(checkpoints);
    service.setConversationTurnStore(turns);
    return service;
  }

  private CheckpointException full() {
    return CheckpointException.storageCapacity(8, 10, 1000, 25, 1024);
  }

  private List<AgentEvent> terminals() {
    return events.stream()
        .filter(
            e ->
                Set.of(AgentEventType.AGENT_RUN_FAILED, AgentEventType.AGENT_RUN_COMPLETED)
                    .contains(e.type()))
        .toList();
  }

  @Test
  void wrappedStreamingCapacityFailureSurvivesFailedTerminalSaveAndIsEmittedOnce() {
    var first = full();
    doThrow(CheckpointException.storageCapacity(9, 10, 1001, 35, 1024))
        .when(checkpoints)
        .finish(owner, "FAILED");
    var service = service(Flux.error(new IllegalStateException("PRIVATE wrapper", first)));
    var result = service.execute(owner, "work", new UserInterventionQueue());
    assertThat(result.success()).isFalse();
    assertThat(result.stopCode()).isEqualTo("checkpoint_capacity");
    assertThat(result.errorMessage()).isEqualTo(first.getMessage()).doesNotContain("PRIVATE");
    assertThat(terminals()).hasSize(1);
    assertThat(terminals().getFirst().payload()).isInstanceOf(AgentRunFailedPayload.class);
    assertThat(((AgentRunFailedPayload) terminals().getFirst().payload()).error().message())
        .isEqualTo(first.getMessage());
    assertThat(turns.read("session").getLast().status())
        .isEqualTo(ConversationTurnStore.Status.FAILED);
    verify(checkpoints).finish(owner, "FAILED");
  }

  @Test
  void initialSaveCapacityFailureDoesNotCallModelAndProvidesActionableTerminalEvent() {
    var first = full();
    doThrow(first).when(checkpoints).start(owner, "work");
    var service = service(Flux.empty());
    var result = service.execute(owner, "work", new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("checkpoint_capacity");
    assertThat(result.errorMessage()).isEqualTo(first.getMessage());
    assertThat(modelCalls).hasValue(0);
    assertThat(terminals()).hasSize(1);
    verify(checkpoints).finish(owner, "FAILED");
  }

  @Test
  void failedCompletionSaveIsReportedBeforeAnyCompletedEvent() {
    doThrow(full()).when(checkpoints).finish(owner, "COMPLETED");
    var service =
        service(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))));
    var result = service.execute(owner, "work", new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("checkpoint_capacity");
    assertThat(terminals()).hasSize(1);
    assertThat(terminals().getFirst().type()).isEqualTo(AgentEventType.AGENT_RUN_FAILED);
    assertThat(turns.read("session").getLast().status())
        .isEqualTo(ConversationTurnStore.Status.FAILED);
    verify(checkpoints).finish(owner, "COMPLETED");
    verify(checkpoints, never()).finish(owner, "FAILED");
  }

  @Test
  void arbitraryFailureMessagesAndNonFactoryCheckpointMessagesRemainPrivate() {
    var service = service(Flux.error(new IllegalStateException("PRIVATE MODEL MESSAGE")));
    assertThat(service.execute(owner, "work", new UserInterventionQueue()).errorMessage())
        .contains("必要な状態")
        .doesNotContain("PRIVATE", "chat run failed");
    service =
        service(
            Flux.error(
                new CheckpointException(
                    CheckpointException.Code.CAPACITY, "PRIVATE CAPACITY MESSAGE")));
    assertThat(
            service
                .execute(
                    new AgentRunContext("run2", "session2", Path.of("."), "project"),
                    "work",
                    new UserInterventionQueue())
                .errorMessage())
        .contains("/checkpoint status")
        .doesNotContain("PRIVATE");
  }
}
