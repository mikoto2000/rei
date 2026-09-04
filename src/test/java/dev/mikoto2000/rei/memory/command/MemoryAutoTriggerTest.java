package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class MemoryAutoTriggerTest {

  @Test
  void chatRendersSuggestionThroughShellAgentEventRendererWhenAutoTriggerEnabled() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    MemoryConsolidatorService memoryConsolidatorService = Mockito.mock(MemoryConsolidatorService.class);

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
      new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), Optional.of(memoryConsolidatorService),
          new AgentEventFactory(Clock.systemDefaultZone()), bus)).execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(output.text().contains("/memory consolidate"));
    assertTrue(directOut.toString().isBlank());
  }

  @Test
  void chatDoesNotPrintSuggestionWhenServiceMissing() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
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
      new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), Optional.empty())).execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(!out.toString().contains("[memory]"));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(java.util.List.of(new Generation(new AssistantMessage(text))));
  }

  private static final class RecordingOutput implements ShellEventOutput {
    private final StringBuilder value = new StringBuilder();
    public void print(String text) { value.append(text); }
    public void println(String text) { value.append(text).append('\n'); }
    public void flush() { }
    String text() { return value.toString(); }
  }
}
