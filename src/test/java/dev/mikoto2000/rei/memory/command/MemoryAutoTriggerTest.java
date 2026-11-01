package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** 記憶自動実行条件の結果と状態の契約を検証するテスト。 */
class MemoryAutoTriggerTest {
  /** 標準出力の表示契約と診断ログを混同しないため、元のログレベルを保存する。 */
  private ch.qos.logback.classic.Level previousDiagnosticLevel;

  /** Spring起動済みの他テストに依存せず、表示のassertionを検証する。 */
  @BeforeEach
  void isolateDiagnosticLogging() {
    var root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    previousDiagnosticLevel = root.getLevel();
    root.setLevel(ch.qos.logback.classic.Level.WARN);
  }

  /** 他クラスにログ設定を持ち越さない。 */
  @AfterEach
  void restoreDiagnosticLogging() {
    var root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.setLevel(previousDiagnosticLevel);
  }

  /** 長期記憶の活用で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void chatRendersSuggestionThroughShellAgentEventRendererWhenAutoTriggerEnabled() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    MemoryConsolidatorService memoryConsolidatorService =
        Mockito.mock(MemoryConsolidatorService.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));
    when(memoryConsolidatorService.shouldSuggestConsolidationNow()).thenReturn(true);

    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    RecordingOutput output = new RecordingOutput();
    bus.subscribe(new ShellAgentEventRenderer(output));
    ByteArrayOutputStream directOut = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(directOut));
    try {
      new CommandLine(
              new ChatCommand(
                  chatClient,
                  modelHolderService,
                  cancellationService,
                  Mockito.mock(ChatResponseNarrator.class),
                  Optional.of(memoryConsolidatorService),
                  new AgentEventFactory(Clock.systemDefaultZone()),
                  bus))
          .execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(output.text().contains("/memory consolidate"), output.text());
    assertTrue(directOut.toString().isBlank());
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void chatDoesNotPrintSuggestionWhenServiceMissing() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      new CommandLine(
              new ChatCommand(
                  chatClient,
                  modelHolderService,
                  cancellationService,
                  Mockito.mock(ChatResponseNarrator.class),
                  Optional.empty()))
          .execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(!out.toString().contains("[memory]"));
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String text) {
    return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text))));
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
}
