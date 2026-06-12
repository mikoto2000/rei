package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.memory.service.MemoryConsolidatorService;
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
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("answer "), response("text")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty())).execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("=== answer("));
    assertTrue(output.contains(" s) ==="));
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
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> disposed.set(true)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      ChatCommand command = new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty()) {
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
    assertTrue(output.contains("[error]"));
    assertTrue(disposed.get());
  }

  @Test
  void runPrintsChatFailureToStandardError() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.error(new java.net.ConnectException("Connection refused")));

    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(err));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty())).execute("hello") == 0);
    } finally {
      System.setErr(originalErr);
    }

    String errorOutput = err.toString();
    assertTrue(errorOutput.contains("[error]"));
    assertTrue(errorOutput.contains("Connection refused"));
  }

  @Test
  void runSendsPromptContainingAttachmentToken() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService, Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty()))
          .execute("`@file:path/to/file.txt`", "please") == 0);
    } finally {
      System.setOut(originalOut);
    }

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    Mockito.verify(chatClient).prompt(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("`@file:path/to/file.txt` please"));
  }

  @Test
  void runPrintsMemorySuggestionWhenAutoTriggerIsTrue() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    MemoryConsolidatorService memoryConsolidatorService = Mockito.mock(MemoryConsolidatorService.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));
    when(memoryConsolidatorService.shouldSuggestConsolidationNow()).thenReturn(true);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.of(memoryConsolidatorService)))
          .execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("[memory] 記憶整理を実行することをお勧めします。"));
  }

  @Test
  void runDoesNotPrintMemorySuggestionWhenAutoTriggerIsFalse() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    MemoryConsolidatorService memoryConsolidatorService = Mockito.mock(MemoryConsolidatorService.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("ok")));
    when(memoryConsolidatorService.shouldSuggestConsolidationNow()).thenReturn(false);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.of(memoryConsolidatorService)))
          .execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(!out.toString().contains("[memory]"));
  }

  @Test
  void runPrintsThinkingMetadataBeforeAnswer() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(
        responseWithThinking("", "考えています"),
        response("answer")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty())).execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("=== thinking ==="));
    assertTrue(output.contains("考えています"));
    assertTrue(output.contains("=== answer("));
    assertTrue(output.indexOf("=== thinking ===") < output.indexOf("=== answer("));
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static ChatResponse responseWithThinking(String text, String thinking) {
    ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
        .metadata("reasoning_content", thinking)
        .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
  }
}
