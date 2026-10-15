package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.chatgpt.*;
import dev.mikoto2000.rei.core.service.*;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ChatExecutionChatGptFailureTest {
  private final AgentRunContext owner =
      new AgentRunContext("run", "session", Path.of("."), "project");
  private final List<AgentEvent> events = new ArrayList<>();
  private final AtomicInteger modelCalls = new AtomicInteger();

  private ChatExecutionService service(Throwable error) {
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
            return Flux.error(error);
          }
        };
    var models = mock(ModelHolderService.class);
    when(models.get()).thenReturn("test");
    return new ChatExecutionService(
        ChatClient.builder(model).build(),
        models,
        new CommandCancellationService(),
        Optional.empty(),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
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
  void wrappedModelDenialHasOneActionableFailureEventAndDoesNotRetry() {
    var refusal = ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
    var result =
        service(new IllegalStateException("PRIVATE wrapper model=PRIVATE key=PRIVATE", refusal))
            .execute(owner, "work", new UserInterventionQueue());
    assertThat(result.success()).isFalse();
    assertThat(result.stopCode()).isEqualTo("chatgpt_permission_denied");
    assertThat(result.errorMessage())
        .contains(
            "MODEL_NOT_ALLOWED",
            "rei.chatgpt-plan.auto-delegation.allowed-models",
            "modelId をホストが許可したモデルに修正")
        .doesNotContain("PRIVATE", "chat run failed");
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
    var terminal = (AgentRunFailedPayload) terminals().getFirst().payload();
    assertThat(terminal.error().code()).isEqualTo(result.stopCode());
    assertThat(terminal.error().message()).isEqualTo(result.errorMessage());
  }

  @Test
  void safeTypedFailureIsNotReplacedByTerminalCheckpointCapacityFailure() {
    var refusal = ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
    var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
    doThrow(
            dev.mikoto2000.rei.checkpoint.CheckpointException.storageCapacity(
                8, 10, 1000, 25, 1024))
        .when(checkpoints)
        .finish(owner, "FAILED");
    var service = service(new IllegalStateException("PRIVATE wrapper", refusal));
    service.setCheckpoints(checkpoints);
    var result = service.execute(owner, "work", new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("chatgpt_permission_denied");
    assertThat(result.errorMessage()).contains("MODEL_NOT_ALLOWED");
    assertThat(terminals()).hasSize(1);
    verify(checkpoints).finish(owner, "FAILED");
  }

  @Test
  void typedFailureOutsideTheResponseStreamAlsoProducesItsControlledTerminalDiagnostic() {
    var refusal = ChatGptException.permission(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED);
    var checkpoints = mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointService.class);
    doThrow(new IllegalStateException("PRIVATE outer callback", refusal))
        .when(checkpoints)
        .start(owner, "work");
    var service = service(new IllegalStateException("unused"));
    service.setCheckpoints(checkpoints);
    var result = service.execute(owner, "work", new UserInterventionQueue());
    assertThat(modelCalls).hasValue(0);
    assertThat(result.stopCode()).isEqualTo("chatgpt_permission_denied");
    assertThat(result.errorMessage()).contains("MODEL_NOT_ALLOWED").doesNotContain("PRIVATE");
    assertThat(terminals()).hasSize(1);
    verify(checkpoints).finish(owner, "FAILED");
  }

  @Test
  void arbitraryFailuresRemainRedacted() {
    var result =
        service(new IllegalStateException("PRIVATE model response"))
            .execute(owner, "work", new UserInterventionQueue());
    assertThat(result.errorMessage())
        .contains("必要な状態")
        .doesNotContain("PRIVATE", "chat run failed");
    assertThat(result.stopCode()).isEqualTo("invalid_state");
    assertThat(terminals()).hasSize(1);
    assertThat(events.toString()).doesNotContain("PRIVATE");
  }
}
