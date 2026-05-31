package dev.mikoto2000.rei.memory.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.command.ChatCommand;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
import dev.mikoto2000.rei.sound.ChatResponseNarrator;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class MemoryAutoTriggerTest {

  @Test
  void chatPrintsSuggestionWhenAutoTriggerEnabled() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    MemoryConsolidatorService memoryConsolidatorService = Mockito.mock(MemoryConsolidatorService.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("ok"));
    when(memoryConsolidatorService.shouldSuggestConsolidationNow()).thenReturn(true);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), Optional.of(memoryConsolidatorService))).execute("hello");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("/memory consolidate"));
  }

  @Test
  void chatDoesNotPrintSuggestionWhenServiceMissing() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("ok"));

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
}
