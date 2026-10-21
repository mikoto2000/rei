package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

/** チャットコマンドイベントの結果と状態の契約を検証するテスト。 */
class ChatCommandEventTest {

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));

  /** 検証に使う生成元。 */
  private final AgentEventFactory factory = new AgentEventFactory(clock);

  /** 検証に使う通知の連携先。 */
  private final InMemoryAgentEventBus bus = new InMemoryAgentEventBus();

  /** 検証に使う受信。 */
  private final List<AgentEvent> received = new ArrayList<>();

  /** 実行について、実行を通知することを確認する。 */
  @Test
  void runEmitsRunAndMessageLifecycleOnSuccess() {
    bus.subscribe(received::add);

    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse())
        .thenReturn(Flux.just(response("answer "), response("text")));

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
                  java.util.Optional.empty(),
                  factory,
                  bus))
          .execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    // The fixed ChatClient used by this unit test bypasses LlmModelProvider instrumentation.
    assertEquals(AgentEventType.AGENT_RUN_STARTED, received.get(0).type());
    assertEquals(AgentEventType.MESSAGE_STARTED, received.get(1).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(2).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(3).type());
    assertEquals(AgentEventType.MESSAGE_COMPLETED, received.get(4).type());
    assertEquals(AgentEventType.AGENT_RUN_COMPLETED, received.get(5).type());
  }

  /** 実行について、失敗終端イベントを通知することを確認する。 */
  @Test
  void runEmitsFailedTerminalEventWhenResponseFails() {
    bus.subscribe(received::add);
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse())
        .thenReturn(Flux.error(new IllegalStateException("boom")));

    new CommandLine(
            new ChatCommand(
                chatClient,
                modelHolderService,
                new CommandCancellationService(),
                Mockito.mock(ChatResponseNarrator.class),
                java.util.Optional.empty(),
                factory,
                bus))
        .execute("hello");

    assertEquals(AgentEventType.AGENT_RUN_STARTED, received.getFirst().type());
    assertEquals(AgentEventType.AGENT_RUN_FAILED, received.getLast().type());
  }

  /** 実行について、推論受付から終了までの管理を通知することを確認する。 */
  @Test
  void runEmitsThinkingLifecycleBeforeMessageLifecycle() {
    bus.subscribe(received::add);
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    ChatGenerationMetadata thinkingMetadata =
        ChatGenerationMetadata.builder().metadata("reasoning_content", "考えています").build();
    when(requestSpec.stream().chatResponse())
        .thenReturn(
            Flux.just(
                new ChatResponse(
                    List.of(new Generation(new AssistantMessage(""), thinkingMetadata))),
                response("回答")));

    new CommandLine(
            new ChatCommand(
                chatClient,
                modelHolderService,
                new CommandCancellationService(),
                Mockito.mock(ChatResponseNarrator.class),
                java.util.Optional.empty(),
                factory,
                bus))
        .execute("hello");

    assertEquals(AgentEventType.THINKING_STARTED, received.get(1).type());
    assertEquals(AgentEventType.THINKING_DELTA, received.get(2).type());
    assertEquals(AgentEventType.THINKING_COMPLETED, received.get(3).type());
    assertEquals(AgentEventType.MESSAGE_STARTED, received.get(4).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(5).type());
    assertEquals(AgentEventType.MESSAGE_COMPLETED, received.get(6).type());
  }

  /** 共通の実行基盤で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void completedRunContainsCompletionTokenUsage() {
    bus.subscribe(received::add);
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec =
        Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    ChatResponseMetadata metadata =
        ChatResponseMetadata.builder().usage(new DefaultUsage(0, 42)).build();
    when(requestSpec.stream().chatResponse())
        .thenReturn(
            Flux.just(
                response("an"),
                new ChatResponse(List.of(new Generation(new AssistantMessage("swer"))), metadata)));

    new CommandLine(
            new ChatCommand(
                chatClient,
                modelHolderService,
                new CommandCancellationService(),
                Mockito.mock(ChatResponseNarrator.class),
                java.util.Optional.empty(),
                factory,
                bus))
        .execute("hello");

    AgentRunCompletedPayload payload = (AgentRunCompletedPayload) received.getLast().payload();
    assertEquals(42L, payload.completionTokens());
    org.junit.jupiter.api.Assertions.assertNotNull(payload.timeToFirstTokenMillis());
    org.junit.jupiter.api.Assertions.assertNotNull(payload.outputTokensPerSecond());
    org.junit.jupiter.api.Assertions.assertTrue(payload.outputTokensPerSecond() > 0.0d);
    org.junit.jupiter.api.Assertions.assertNotNull(payload.endToEndTokensPerSecond());
    org.junit.jupiter.api.Assertions.assertTrue(payload.endToEndTokensPerSecond() > 0.0d);
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
