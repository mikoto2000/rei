package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.ui.shell.ChatCommand;
import dev.mikoto2000.rei.ui.shell.ShellAgentEventRenderer;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import dev.mikoto2000.rei.ui.shell.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class ChatCommandCancellationTest {

  @Test
  void runStopsStreamingWhenCancelled() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    CountDownLatch subscribed = new CountDownLatch(1);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.concat(
        Flux.just(response("partial ")),
        Flux.<ChatResponse>never()
            .doOnSubscribe(ignored -> subscribed.countDown())));

    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    RecordingOutput output = new RecordingOutput();
    bus.subscribe(new ShellAgentEventRenderer(output));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var future = executor.submit(() ->
          new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
              Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty(),
              new AgentEventFactory(Clock.systemDefaultZone()), bus)).execute("hello"));
      assertTrue(subscribed.await(1, TimeUnit.SECONDS));

      cancellationService.cancel();

      future.get(1, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertTrue(output.text().contains("partial "));
    assertTrue(output.text().contains("[agent] failed: chat run cancelled"));
  }

  private static final class RecordingOutput implements ShellEventOutput {
    private final StringBuilder value = new StringBuilder();
    public void print(String text) { value.append(text); }
    public void println(String text) { value.append(text).append('\n'); }
    public void flush() { }
    String text() { return value.toString(); }
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }
}
