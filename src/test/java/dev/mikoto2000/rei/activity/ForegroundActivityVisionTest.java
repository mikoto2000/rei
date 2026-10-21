package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 前面作業状況画像認識の結果と状態の契約を検証するテスト。 */
class ForegroundActivityVisionTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void logsReportedReasoningTokensWithoutReasoningText() throws Exception {
    var usage =
        com.openai.models.completions.CompletionUsage.builder()
            .completionTokens(1800)
            .promptTokens(100)
            .totalTokens(1900)
            .completionTokensDetails(
                com.openai.models.completions.CompletionUsage.CompletionTokensDetails.builder()
                    .reasoningTokens(1500)
                    .build())
            .build();
    var metadata =
        org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
            .usage(new org.springframework.ai.chat.metadata.DefaultUsage(100, 1800, 1900, usage))
            .build();
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(response(VALID, "stop").getResults(), metadata));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try (var scope = org.slf4j.MDC.putCloseable("activityScope", "foreground")) {
      new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build())
          .extract(
              ActivityCaptureTest.screen(20),
              new ForegroundWindow("Firefox", 1, "PRIVATE_TITLE", "1"));
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getFormattedMessage().contains("reasoning_tokens=1500")
                          && e.getFormattedMessage().contains("max_output_tokens=2048")));
      assertTrue(
          appender.list.stream()
              .noneMatch(
                  e ->
                      e.getFormattedMessage().contains("PRIVATE_TITLE")
                          || e.getFormattedMessage().contains(VALID)));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 検証に使う有効な。 */
  static final String VALID =
      """
      {"category":"research","application":"Firefox","service":"ChatGPT","projectCandidate":null,"contentCandidate":null,"summary":"調査に関する画面","confidence":0.9}
      """;

  /** 前面について、単一小さな分類を利用することを確認する。 */
  @Test
  void foregroundUsesOneSmallClassificationWithoutModelGeneratedObservationsOrMonitorIds()
      throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(response(VALID, "stop"));
    try (var scope = org.slf4j.MDC.putCloseable("activityScope", "foreground")) {
      var result =
          new VisionActivityExtractor(
                  () -> model,
                  () ->
                      OpenAiChatOptions.builder()
                          .model("local-activity")
                          .temperature(.15)
                          .maxTokens(32768)
                          .build())
              .extract(
                  ActivityCaptureTest.screen(20),
                  new ForegroundWindow("Firefox", 1, "ChatGPT", "1"));
      assertEquals(1, result.inference().activities().size());
      assertEquals("research", result.inference().activities().getFirst().type());
      assertEquals("", result.inference().activities().getFirst().projectCandidate());
    }
    var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
    verify(model).call(prompt.capture());
    var options = (OpenAiChatOptions) prompt.getValue().getOptions();
    assertEquals(2048, options.getMaxCompletionTokens());
    assertNull(options.getMaxTokens());
    assertEquals("local-activity", options.getModel());
    assertEquals(.15, options.getTemperature());
    var schema =
        new tools.jackson.databind.json.JsonMapper()
            .readTree(options.getResponseFormat().getJsonSchema());
    assertEquals(7, schema.get("properties").size());
    assertFalse(schema.get("properties").has("observations"));
    assertFalse(schema.get("properties").has("activities"));
    assertFalse(schema.get("properties").has("monitor"));
    assertTrue(prompt.getValue().getSystemMessage().getText().length() < 1200);
    assertTrue(options.getToolCallbacks().isEmpty());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void tokenFixtureFailsAt1024AndSucceedsAt2048WithoutRepeatingTruncatedRequest() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenAnswer(
            i ->
                response(
                    ((OpenAiChatOptions) ((Prompt) i.getArgument(0)).getOptions())
                                .getMaxCompletionTokens()
                            < 2048
                        ? ""
                        : VALID,
                    ((OpenAiChatOptions) ((Prompt) i.getArgument(0)).getOptions())
                                .getMaxCompletionTokens()
                            < 2048
                        ? "length"
                        : "stop"));
    try (var scope = org.slf4j.MDC.putCloseable("activityScope", "foreground")) {
      var low =
          new VisionActivityExtractor(
              () -> model, () -> OpenAiChatOptions.builder().build(), .5, 1024);
      var error =
          assertThrows(
              ActivityOutputParser.InvalidOutput.class,
              () ->
                  low.extract(
                      ActivityCaptureTest.screen(20),
                      new ForegroundWindow("Firefox", 1, "ChatGPT", "1")));
      assertEquals("/:output_limit", error.diagnostic());
      verify(model).call(any(Prompt.class));
      var normal =
          new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
      assertEquals(
          "research",
          normal
              .extract(
                  ActivityCaptureTest.screen(20),
                  new ForegroundWindow("Firefox", 1, "ChatGPT", "1"))
              .inference()
              .activities()
              .getFirst()
              .type());
      verify(model, times(2)).call(any(Prompt.class));
    }
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void lightweightValidationRejectsExtraFieldsTrailingJsonAndInvalidCategories() {
    var parser = new ForegroundActivityParser();
    for (String json :
        List.of(
            VALID + " {}",
            VALID.replace("research", "not-a-category"),
            VALID.replace("\"confidence\":0.9", "\"confidence\":1.1"),
            VALID.replace("\"confidence\":0.9", "\"confidence\":0.9,\"observations\":[]")))
      assertThrows(ActivityOutputParser.InvalidOutput.class, () -> parser.parse(json, "m1"));
  }

  /** 検証で使用する応答を、指定された条件に合わせて用意する。 */
  private static ChatResponse response(String content, String finish) {
    return new ChatResponse(
        List.of(
            new Generation(
                new AssistantMessage(content),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                    .finishReason(finish)
                    .build())));
  }
}
