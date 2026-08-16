package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.llm.LlmProperties;
import dev.mikoto2000.rei.llm.OutputLimitReplanPlan;
import dev.mikoto2000.rei.llm.OutputLimitReplanRequest;
import dev.mikoto2000.rei.llm.OutputLimitReplanSubgoal;
import dev.mikoto2000.rei.llm.OutputLimitReplanner;
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
  void runPrintsTokensPerSecondWhenCompletionUsageIsAvailable() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(
        response("answer "),
        responseWithUsage("", 12)));

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
    assertTrue(output.contains("answer "));
    assertTrue(output.contains("=== speed("));
    assertTrue(output.contains(" tok/s) ==="));
  }

  @Test
  void runDoesNotPrintTokensPerSecondWhenCompletionUsageIsUnavailable() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(response("answer")));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          Mockito.mock(ChatResponseNarrator.class), java.util.Optional.empty())).execute("hello") == 0);
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(!out.toString().contains(" tok/s"));
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
  void runTreatsLengthFinishReasonAsOutputLimitReached() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    ChatResponseNarrator narrator = Mockito.mock(ChatResponseNarrator.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(responseWithFinishReason("partial", "length")));

    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(err));
    try {
      assertTrue(new CommandLine(new ChatCommand(chatClient, modelHolderService, cancellationService,
          narrator, java.util.Optional.empty())).execute("hello") == 0);
    } finally {
      System.setErr(originalErr);
    }

    assertTrue(err.toString().contains("output token limit"));
    verify(narrator, never()).narrateIfCompleted(any());
  }

  @Test
  void runReplansAndExecutesSubgoalsWhenOutputLimitIsReached() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    ChatResponseNarrator narrator = Mockito.mock(ChatResponseNarrator.class);
    OutputLimitReplanner replanner = Mockito.mock(OutputLimitReplanner.class);
    LlmProperties properties = new LlmProperties();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(
        Flux.just(responseWithFinishReason("partial", "length")),
        Flux.just(response("subgoal-1 result")),
        Flux.just(response("subgoal-2 result")),
        Flux.just(response("final result")));
    when(replanner.replan(any(OutputLimitReplanRequest.class))).thenReturn(new OutputLimitReplanPlan(List.of(
        new OutputLimitReplanSubgoal("one", "first subgoal"),
        new OutputLimitReplanSubgoal("two", "second subgoal")),
        "integrate results"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(
          new ChatCommand.FixedLlmChatClientProvider(chatClient),
          modelHolderService,
          new ChatCommand.FixedLlmModelProvider(),
          properties,
          cancellationService,
          narrator,
          java.util.Optional.empty(),
          java.util.Optional.of(replanner))).execute("original goal") == 0);
    } finally {
      System.setOut(originalOut);
    }

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient, Mockito.times(4)).prompt(promptCaptor.capture());
    assertTrue(promptCaptor.getAllValues().get(0).getContents().contains("original goal"));
    assertTrue(promptCaptor.getAllValues().get(1).getContents().contains("first subgoal"));
    assertTrue(promptCaptor.getAllValues().get(2).getContents().contains("second subgoal"));
    assertTrue(promptCaptor.getAllValues().get(3).getContents().contains("integrate results"));
    assertTrue(promptCaptor.getAllValues().get(3).getContents().contains("subgoal-1 result"));
    assertTrue(promptCaptor.getAllValues().get(3).getContents().contains("subgoal-2 result"));
    verify(narrator).narrateIfCompleted("final result");
  }

  @Test
  void runCountsInitialPromptAgainstOutputLimitLlmCallBudget() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    ChatResponseNarrator narrator = Mockito.mock(ChatResponseNarrator.class);
    OutputLimitReplanner replanner = Mockito.mock(OutputLimitReplanner.class);
    LlmProperties properties = new LlmProperties();
    properties.getOutputLimit().setMaxLlmCallsPerRun(1);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(Flux.just(responseWithFinishReason("partial", "length")));

    ByteArrayOutputStream err = new ByteArrayOutputStream();
    PrintStream originalErr = System.err;
    System.setErr(new PrintStream(err));
    try {
      assertTrue(new CommandLine(new ChatCommand(
          new ChatCommand.FixedLlmChatClientProvider(chatClient),
          modelHolderService,
          new ChatCommand.FixedLlmModelProvider(),
          properties,
          cancellationService,
          narrator,
          java.util.Optional.empty(),
          java.util.Optional.of(replanner))).execute("original goal") == 0);
    } finally {
      System.setErr(originalErr);
    }

    verify(replanner, never()).replan(any());
    verify(chatClient, Mockito.times(1)).prompt(any(Prompt.class));
    assertTrue(err.toString().contains("LLM call budget exhausted"));
    verify(narrator, never()).narrateIfCompleted(any());
  }

  @Test
  void runReplansSubgoalAgainWhenSubgoalHitsOutputLimit() {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    ChatResponseNarrator narrator = Mockito.mock(ChatResponseNarrator.class);
    OutputLimitReplanner replanner = Mockito.mock(OutputLimitReplanner.class);
    LlmProperties properties = new LlmProperties();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().chatResponse()).thenReturn(
        Flux.just(responseWithFinishReason("partial", "length")),
        Flux.just(responseWithFinishReason("large subgoal partial", "length")),
        Flux.just(response("nested subgoal result")),
        Flux.just(response("nested final result")),
        Flux.just(response("final result")));
    when(replanner.replan(any(OutputLimitReplanRequest.class))).thenReturn(
        new OutputLimitReplanPlan(List.of(new OutputLimitReplanSubgoal("one", "large subgoal")), "integrate large"),
        new OutputLimitReplanPlan(List.of(new OutputLimitReplanSubgoal("one-a", "small subgoal")), "integrate small"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      assertTrue(new CommandLine(new ChatCommand(
          new ChatCommand.FixedLlmChatClientProvider(chatClient),
          modelHolderService,
          new ChatCommand.FixedLlmModelProvider(),
          properties,
          cancellationService,
          narrator,
          java.util.Optional.empty(),
          java.util.Optional.of(replanner))).execute("original goal") == 0);
    } finally {
      System.setOut(originalOut);
    }

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient, Mockito.times(5)).prompt(promptCaptor.capture());
    assertTrue(promptCaptor.getAllValues().get(1).getContents().contains("large subgoal"));
    assertTrue(promptCaptor.getAllValues().get(2).getContents().contains("small subgoal"));
    assertTrue(promptCaptor.getAllValues().get(4).getContents().contains("nested final result"));
    verify(replanner, Mockito.times(2)).replan(any());
    verify(narrator).narrateIfCompleted("final result");
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

  private static ChatResponse responseWithFinishReason(String text, String finishReason) {
    ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
        .finishReason(finishReason)
        .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
  }

  private static ChatResponse responseWithUsage(String text, int completionTokens) {
    ChatResponseMetadata metadata = ChatResponseMetadata.builder()
        .usage(new DefaultUsage(0, completionTokens))
        .build();
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
  }
}
