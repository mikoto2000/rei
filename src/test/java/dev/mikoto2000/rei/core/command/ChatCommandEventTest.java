package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunCompletedPayload;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class ChatCommandEventTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T16:30:20Z"), ZoneId.of("Asia/Tokyo"));
  private final AgentEventFactory factory = new AgentEventFactory(clock);
  private final InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
  private final List<AgentEvent> received = new ArrayList<>();

  @Test
  void runEmitsRunAndMessageLifecycleOnSuccess() {
    bus.subscribe(received::add);

    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("answer "), response("text")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty(), factory, bus)).execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    // agent.run.started → message.started → message.delta → message.delta → message.completed → agent.run.completed
    assertEquals(AgentEventType.AGENT_RUN_STARTED, received.get(0).type());
    assertEquals(AgentEventType.MESSAGE_STARTED, received.get(1).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(2).type());
    assertEquals(AgentEventType.MESSAGE_DELTA, received.get(3).type());
    assertEquals(AgentEventType.MESSAGE_COMPLETED, received.get(4).type());
    assertEquals(AgentEventType.AGENT_RUN_COMPLETED, received.get(5).type());
  }

  @Test
  void runEmitsFailedTerminalEventWhenResponseFails() {
    bus.subscribe(received::add);
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.error(new IllegalStateException("boom")));

    new CommandLine(new ChatCommand(chatClient, modelHolderService, new CommandCancellationService(),
        Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty(), factory, bus)).execute("hello");

    assertEquals(AgentEventType.AGENT_RUN_STARTED, received.getFirst().type());
    assertEquals(AgentEventType.AGENT_RUN_FAILED, received.getLast().type());
  }

  @Test
  void completedRunContainsCompletionTokenUsage() {
    bus.subscribe(received::add);
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
        .usage(new DefaultUsage(0, 42))
        .build();
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(
        new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))), metadata)));

    new CommandLine(new ChatCommand(chatClient, modelHolderService, new CommandCancellationService(),
        Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty(), factory, bus)).execute("hello");

    AgentRunCompletedPayload payload = (AgentRunCompletedPayload) received.getLast().payload();
    assertEquals(42L, payload.completionTokens());
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
