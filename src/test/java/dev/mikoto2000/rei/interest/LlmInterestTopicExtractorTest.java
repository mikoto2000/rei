package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;

/** 言語モデル関心話題情報の抽出の結果と状態の契約を検証するテスト。 */
class LlmInterestTopicExtractorTest {
  @Test
  void laterConfigurationMutationCannotExpandTheValidatedInputBudget() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new InterestProperties();
    properties.setExtractionMaxInputTokens(1024);
    var extractor = boundedExtractor(model, properties);
    properties.setExtractionMaxInputTokens(65536);
    var snippet =
        new ConversationSnippet("synthetic", "source ".repeat(1000), OffsetDateTime.now());
    assertTrue(extractor.extract(List.of(snippet), 3).isEmpty());
    org.mockito.Mockito.verifyNoInteractions(model);
  }

  @Test
  void wholeRecentEvidenceAndPastQueriesFitTheCompletePromptBudget() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new InterestProperties();
    properties.setExtractionMaxInputTokens(1024);
    var extractor = boundedExtractor(model, properties);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("[]"))))));
    var at = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    String giant = "日本語😀".repeat(100000);
    var newest =
        new ConversationSnippet(
            "recent", "NEWEST useful evidence " + "x".repeat(250), at.plusDays(2));
    var older = new ConversationSnippet("older", "OLDEST evidence " + "y".repeat(250), at);
    var snippets = List.of(older, new ConversationSnippet("huge", giant, at.plusDays(3)), newest);
    assertTrue(extractor.extract(snippets, 3, List.of(giant, "safe past query")).isEmpty());
    var prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(model).stream(prompt.capture());
    String text = prompt.getValue().getContents();
    assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 32 <= 1024);
    assertTrue(text.contains("NEWEST useful evidence"));
    assertTrue(!text.contains("OLDEST evidence"));
    assertTrue(!text.contains(giant));
    assertTrue(text.contains("omitted 2 whole snippets"));
    assertEquals(giant, snippets.get(1).text());
    var options = (org.springframework.ai.openai.OpenAiChatOptions) prompt.getValue().getOptions();
    assertEquals(4096, options.getMaxTokens());
    assertTrue(options.getToolCallbacks().isEmpty());
  }

  @Test
  void noFittingEvidenceDoesNotStartAnInferenceRequest() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new InterestProperties();
    properties.setExtractionMaxInputTokens(1024);
    var extractor = boundedExtractor(model, properties);
    assertTrue(
        extractor
            .extract(
                List.of(
                    new ConversationSnippet(
                        "source", "x".repeat(1000000), OffsetDateTime.now(ZoneOffset.UTC))),
                3)
            .isEmpty());
    org.mockito.Mockito.verifyNoInteractions(model);
  }

  @Test
  void totalTimeoutCancelsTheStreamInsteadOfLeavingBackgroundInferenceRunning() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new InterestProperties();
    properties.setExtractionTimeoutSeconds(1);
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.<ChatResponse>never()
                .doOnCancel(() -> cancelled.set(true)));
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(3),
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () ->
                    boundedExtractor(model, properties)
                        .extract(
                            List.of(
                                new ConversationSnippet(
                                    "synthetic",
                                    "bounded fact",
                                    OffsetDateTime.now(ZoneOffset.UTC))),
                            3)));
    assertTrue(cancelled.get());
    verify(model).stream(any(Prompt.class));
  }

  @Test
  void completedJsonBeforeALengthFinishIsNotAcceptedAsACompleteExtraction() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    when(model.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))),
                new ChatResponse(
                    List.of(
                        new Generation(
                            new AssistantMessage(""),
                            org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                .finishReason("length")
                                .build())))));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            boundedExtractor(model, new InterestProperties())
                .extract(
                    List.of(
                        new ConversationSnippet(
                            "synthetic", "bounded fact", OffsetDateTime.now(ZoneOffset.UTC))),
                    3));
  }

  @Test
  void blockingStreamFactoryCannotBypassTheTotalDeadline() throws Exception {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var properties = new InterestProperties();
    properties.setExtractionTimeoutSeconds(1);
    var interrupted = new java.util.concurrent.CountDownLatch(1);
    when(model.stream(any(Prompt.class)))
        .thenAnswer(invocation -> {
          try {
            Thread.sleep(10000);
          } catch (InterruptedException error) {
            interrupted.countDown();
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException();
          }
          return reactor.core.publisher.Flux.just(
              new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))));
        });
    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(3),
        () ->
            org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () ->
                    boundedExtractor(model, properties)
                        .extract(
                            List.of(
                                new ConversationSnippet(
                                    "synthetic",
                                    "bounded fact",
                                    OffsetDateTime.now(ZoneOffset.UTC))),
                            3)));
    assertTrue(interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS));
    verify(model).stream(any(Prompt.class));
  }

  @Test
  void inheritedDefaultToolsAreRejectedBeforeStreaming() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
    when(model.getOptions())
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .toolCallbacks(List.of(callback))
                .build());
    var extractor =
        new LlmInterestTopicExtractor(
            model, org.mockito.Mockito.mock(ModelHolderService.class), new JsonMapper());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            extractor.extract(
                List.of(
                    new ConversationSnippet(
                        "synthetic", "bounded fact", OffsetDateTime.now(ZoneOffset.UTC))),
                3));
    verify(model, org.mockito.Mockito.never()).stream(any(Prompt.class));
  }

  @Test
  void rawSdkToolsCannotEscapeTheInputAndExecutionBoundary() {
    var model = org.mockito.Mockito.mock(ChatModel.class);
    var provider = org.mockito.Mockito.mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    when(provider.isolatedChatModel("interest-discovery")).thenReturn(model);
    when(provider.requestOptions("interest-discovery", null,false))
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .extraBody(java.util.Map.of("tools", "unbounded definitions"))
                .build());
    var extractor =
        new LlmInterestTopicExtractor(
            provider,
            org.mockito.Mockito.mock(ModelHolderService.class),
            new JsonMapper(),
            new InterestProperties());
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            extractor.extract(
                List.of(
                    new ConversationSnippet(
                        "synthetic", "bounded fact", OffsetDateTime.now(ZoneOffset.UTC))),
                3));
    org.mockito.Mockito.verifyNoInteractions(model);
  }

  private LlmInterestTopicExtractor boundedExtractor(
      ChatModel model, InterestProperties properties) {
    var provider = org.mockito.Mockito.mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    when(provider.isolatedChatModel("interest-discovery")).thenReturn(model);
    when(provider.requestOptions("interest-discovery", null,false))
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder().maxTokens(16384).build());
    return new LlmInterestTopicExtractor(
        provider, org.mockito.Mockito.mock(ModelHolderService.class), new JsonMapper(), properties);
  }

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void extractBuildsPromptAndParsesJsonResponse() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            new AssistantMessage(
                                """
                                [
                                  {
                                    "topic": "Neovim setup",
                                    "reason": "User talked about editor setup",
                                    "searchQuery": "Neovim devcontainer best practices",
                                    "score": 0.82
                                  }
                                ]
                                """))))));

    List<InterestTopicCandidate> topics =
        extractor.extract(
            List.of(
                new ConversationSnippet(
                    "c1",
                    "vim in devcontainer",
                    OffsetDateTime.of(2026, 4, 17, 0, 0, 0, 0, ZoneOffset.UTC)),
                new ConversationSnippet(
                    "c2",
                    "neovim plugins",
                    OffsetDateTime.of(2026, 4, 18, 0, 0, 0, 0, ZoneOffset.UTC))),
            3);

    assertEquals(1, topics.size());
    assertEquals("Neovim setup", topics.getFirst().topic());
    assertEquals("Neovim devcontainer best practices", topics.getFirst().searchQuery());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(promptCaptor.capture());
    assertTrue(promptCaptor.getValue().getContents().contains("Output must be a JSON array only."));
    assertTrue(promptCaptor.getValue().getContents().contains("vim in devcontainer"));
  }

  /** 関心の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractWithPastQueriesIncludesPastQueriesInPrompt() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("[]"))))));

    List<String> pastQueries = List.of("Neovim devcontainer best practices", "vim plugin 2025");

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        pastQueries);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    assertTrue(prompt.contains("Neovim devcontainer best practices"));
    assertTrue(prompt.contains("vim plugin 2025"));
    assertTrue(prompt.contains("Previously used search queries. Avoid duplicates:"));
  }

  /** 関心の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void extractWithEmptyPastQueriesDoesNotAddPastQueriesSection() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor =
        new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("qwen3.5:122b");
    when(chatModel.stream(any(Prompt.class)))
        .thenReturn(
            reactor.core.publisher.Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("[]"))))));

    extractor.extract(
        List.of(new ConversationSnippet("c1", "neovim", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        List.of());

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).stream(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    assertTrue(!prompt.contains("Previously used search queries. Avoid duplicates:"));
  }

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void normalizeJsonArray_stripsCodeFence() {
    String normalized =
        LlmInterestTopicExtractor.normalizeJsonArray(
            """
            ```json
            [
              {"topic":"t","reason":"r","searchQuery":"q","score":0.7}
            ]
            ```
            """);
    assertTrue(normalized.startsWith("["));
    assertTrue(normalized.endsWith("]"));
  }

  /** 関心の管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void normalizeJsonArray_extractsArrayFromDecoratedText() {
    String normalized =
        LlmInterestTopicExtractor.normalizeJsonArray(
            """
            Here are candidates:
            [
              {"topic":"t","reason":"r","searchQuery":"q","score":0.7}
            ]
            Thanks.
            """);
    assertTrue(normalized.startsWith("["));
    assertTrue(normalized.endsWith("]"));
  }
}
