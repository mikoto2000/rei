package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 作業状況時間計測の結果と状態の契約を検証するテスト。 */
class ActivityTimingTest {
  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void successfulVisionLogsStageDurationsAndSizesWithoutPrivateContent() throws Exception {
    check(false);
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void outputLimitAlsoLogsTimingsAndReasonWithoutProviderOutput() throws Exception {
    check(true);
  }

  /** 検証用に、後続の処理を進められるか、現在の条件を確認する。 */
  private void check(boolean limit) throws Exception {
    var model = mock(ChatModel.class);
    var metadata =
        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
            .finishReason(limit ? "length" : "stop")
            .build();
    when(model.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            limit ? "PRIVATE_RESPONSE" : ActivityExtractionTest.VALID),
                        metadata))));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var extractor =
          new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
      var fg = new ForegroundWindow("firefox", 1, "PRIVATE_TITLE", "1");
      if (limit)
        assertThrows(
            ActivityOutputParser.InvalidOutput.class,
            () -> extractor.extract(ActivityCaptureTest.screen(20), fg));
      else extractor.extract(ActivityCaptureTest.screen(20), fg);
      var events = appender.list.stream().map(e -> e.getFormattedMessage()).toList();
      assertTrue(
          events.stream()
              .anyMatch(
                  s ->
                      s.contains("Activity vision timing")
                          && s.contains("input_prepare_ms=")
                          && s.contains("llm_roundtrip_ms=")
                          && s.contains("output_parse_ms=")
                          && s.contains("png_bytes=")
                          && s.contains("status=" + (limit ? "output_limit" : "success"))));
      assertTrue(
          events.stream()
              .noneMatch(
                  s ->
                      s.contains("PRIVATE_TITLE")
                          || s.contains("PRIVATE_RESPONSE")
                          || s.contains(ActivityExtractionTest.VALID)));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void networkFailureHasRoundtripDurationAndNoPayload() throws Exception {
    var model = mock(ChatModel.class);
    when(model.call(any(Prompt.class)))
        .thenThrow(new IllegalStateException("PRIVATE_NETWORK_DATA"));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThrows(
          IllegalStateException.class,
          () ->
              new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build())
                  .extract(
                      ActivityCaptureTest.screen(20), new ForegroundWindow("app", 1, "", "1")));
      assertTrue(
          appender.list.stream()
              .anyMatch(e -> e.getFormattedMessage().contains("status=request_failed")));
      assertTrue(
          appender.list.stream()
              .noneMatch(
                  e ->
                      e.getFormattedMessage().contains("PRIVATE_NETWORK_DATA")
                          || e.getThrowableProxy() != null));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
