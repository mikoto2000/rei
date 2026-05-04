package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
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
    when(requestSpec.stream().content()).thenReturn(Flux.concat(
        Flux.just("partial "),
        Flux.<String>never()
            .doOnSubscribe(ignored -> subscribed.countDown())));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    var executor = Executors.newSingleThreadExecutor();
    System.setOut(new PrintStream(out));
    try {
      var future = executor.submit(() ->
          new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class))).execute("hello"));
      assertTrue(subscribed.await(1, TimeUnit.SECONDS));

      cancellationService.cancel();

      future.get(1, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("partial "));
    assertTrue(output.contains("[cancelled]"));
  }
}
