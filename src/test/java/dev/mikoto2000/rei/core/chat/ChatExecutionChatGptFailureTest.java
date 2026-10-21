package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.chatgpt.ChatGptException;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** チャット実行チャットGPT失敗情報の結果と状態の契約を検証するテスト。 */
class ChatExecutionChatGptFailureTest {
  /** 検証に使う所有者。 */
  private final AgentRunContext owner =
      new AgentRunContext("run", "session", Path.of("."), "project");

  /** 通知された出来事と順序を検証するための記録。 */
  private final List<AgentEvent> events = new ArrayList<>();

  /** 検証に使うモデル呼び出し回数。 */
  private final AtomicInteger modelCalls = new AtomicInteger();

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private ChatExecutionService service(Throwable error) {
    ChatModel model =
        new ChatModel() {
          /** 検証用に、会話の実行で参照する選択可能な設定を返す。 */
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          /** 検証用に、会話の実行の要求を実行し、呼び出し元へ結果を返す。 */
          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          /** {@code stream} の条件を再現するための補助情報を返す。 */
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

  /** {@code terminals} の条件を再現するための補助情報を返す。 */
  private List<AgentEvent> terminals() {
    return events.stream()
        .filter(
            e ->
                Set.of(AgentEventType.AGENT_RUN_FAILED, AgentEventType.AGENT_RUN_COMPLETED)
                    .contains(e.type()))
        .toList();
  }

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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

  /** 会話の実行で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
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

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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

  /** 会話の実行で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
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
