package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

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

class ChatCommandTest {

  @Test
  void runPrintsAnswerWithoutAdvisorContext() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("answer ", "text"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class))).execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("=== answer ==="));
    assertTrue(output.contains("answer text"));
    assertTrue(output.endsWith(System.lineSeparator()));
  }

  @Test
  void runStopsWaitingWhenStreamDoesNotTerminate() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    AtomicBoolean disposed = new AtomicBoolean(false);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.<String>never().doOnCancel(() -> disposed.set(true)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ChatCommand command = new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class)) {
        @Override
        long streamTimeoutMillis() {
          return 1L;
        }
      };
      assertTrue(new CommandLine(command).execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("[error] 回答の取得がタイムアウトしました"));
    assertTrue(disposed.get());
  }
}
