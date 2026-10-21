package dev.mikoto2000.rei.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.timing.TimingExecution;
import dev.mikoto2000.rei.timing.TimingRecorder;
import dev.mikoto2000.rei.timing.TimingStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** チャット実行時間計測の結果と状態の契約を検証するテスト。 */
class ChatExecutionTimingTest {
  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  ChatExecutionService service(Flux<ChatResponse> stream) {
    var client = mock(ChatClient.class);
    var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
    var models = mock(ModelHolderService.class);
    when(models.get()).thenReturn("test");
    when(client.prompt(any(Prompt.class))).thenReturn(request);
    when(request.advisors(any(java.util.function.Consumer.class))).thenReturn(request);
    when(request.stream().chatResponse()).thenReturn(stream);
    return new ChatExecutionService(
        client, models, new CommandCancellationService(), Optional.empty());
  }

  /** 検証に使う所有者。 */
  final AgentRunContext owner =
      new AgentRunContext("run", "session", java.nio.file.Path.of("."), "project");

  /** 会話の実行で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void realChatBoundaryRecordsTerminalStateWithCaptureAbsent() {
    var store =
        new TimingStore(
            true, 10, 20, 100, Duration.ofHours(1), Clock.systemUTC(), System::nanoTime);
    var chat =
        service(
            Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))))));
    chat.setTiming(new TimingExecution(store));
    var result = chat.execute(owner, "question", new UserInterventionQueue());
    assertTrue(result.success());
    var recorded = store.latest("project", "session").orElseThrow();
    assertEquals(TimingRecorder.Status.SUCCESS, recorded.status());
    assertFalse(recorded.toString().contains("answer"));
    assertFalse(recorded.toString().contains("question"));
  }

  /** 会話の実行で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void recorderFailureDoesNotChangeRealChatFailureOrSuccess() {
    var recorder = mock(TimingRecorder.class);
    when(recorder.enabled()).thenReturn(true);
    when(recorder.beginRun(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("PRIVATE"));
    var chat = service(Flux.error(new IllegalStateException("MODEL-PRIVATE")));
    chat.setTiming(new TimingExecution(recorder));
    assertFalse(chat.execute(owner, "question", new UserInterventionQueue()).success());
  }
}
