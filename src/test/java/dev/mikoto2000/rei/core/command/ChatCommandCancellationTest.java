package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

/** チャットコマンドキャンセルの結果と状態の契約を検証するテスト。 */
class ChatCommandCancellationTest {

  /** 共通の実行基盤の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void runStopsStreamingWhenCancelled() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    CountDownLatch subscribed = new CountDownLatch(1);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse())
        .thenReturn(
            Flux.concat(
                Flux.just(response("partial ")),
                Flux.<ChatResponse>never().doOnSubscribe(ignored -> subscribed.countDown())));

    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    RecordingOutput output = new RecordingOutput();
    bus.subscribe(new ShellAgentEventRenderer(output));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future =
          executor.submit(
              () ->
                  new CommandLine(
                          new ChatCommand(
                              chatClient,
                              modelHolderService,
                              cancellationService,
                              Mockito.mock(ChatResponseNarrator.class),
                              java.util.Optional.empty(),
                              new AgentEventFactory(Clock.systemDefaultZone()),
                              bus))
                      .execute("hello"));
      assertTrue(subscribed.await(1, TimeUnit.SECONDS));

      cancellationService.cancel();

      future.get(1, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertTrue(output.text().contains("partial "));
    assertTrue(output.text().contains("[agent] cancelled"));
  }

  /** 検証条件を再現するためのRecording出力。 */
  private static final class RecordingOutput implements ShellEventOutput {
    /** 検証に使う値。 */
    private final StringBuilder value = new StringBuilder();

    /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
    public void print(String text) {
      value.append(text);
    }

    /** 検証で受け取った案内や結果を、表示の確認先へ出力する。 */
    public void println(String text) {
      value.append(text).append('\n');
    }

    /** 検証で保留している出力の確定を扱う。 */
    public void flush() {}

    /** {@code text} の条件を再現するための補助情報を返す。 */
    String text() {
      return value.toString();
    }
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
