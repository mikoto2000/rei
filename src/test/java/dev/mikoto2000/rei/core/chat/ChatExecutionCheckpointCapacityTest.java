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

/** チャット実行再開用の記録許容件数の結果と状態の契約を検証するテスト。 */
class ChatExecutionCheckpointCapacityTest {
  /** 検証に使う所有者。 */
  private final AgentRunContext owner =
      new AgentRunContext("run", "session", Path.of("."), "project");

  /** 通知された出来事と順序を検証するための記録。 */
  private final List<AgentEvent> events = new ArrayList<>();

  /** 検証に使うモデル呼び出し回数。 */
  private final AtomicInteger modelCalls = new AtomicInteger();

  /** 検証に使う再開用の記録一覧。 */
  private final PersistentCheckpointService checkpoints = mock(PersistentCheckpointService.class);

  /** 検証に使うやり取り一覧。 */
  private final ConversationTurnStore turns = ConversationTurnStore.inMemory();

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private ChatExecutionService service(Flux<ChatResponse> stream) {
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

  /** {@code full} の条件を再現するための補助情報を返す。 */
  private CheckpointException full() {
    return CheckpointException.storageCapacity(8, 10, 1000, 25, 1024);
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

  /** 失敗終端保存の後も対象の情報が残ることを確認する。 */
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

  /** 初期について、許容件数失敗情報なし呼び出しモデルを保存することを確認する。 */
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

  /** 失敗完了について、報告された内容を保存することを確認する。 */
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

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
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
