package dev.mikoto2000.rei.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Vision失敗の原因メタデータを記録し、要求・応答・例外本文を出さないことを検証する。 */
class ActivityVisionFailureLoggingTest {
  private static final String PRIVATE = "PRIVATE_SCREEN_TOKEN_BODY\nforged_log_entry";

  private static RuntimeException sdkFailure() {
    return new IllegalStateException(
        PRIVATE,
        com.openai.errors.BadRequestException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code(PRIVATE)
                    .message(PRIVATE)
                    .param(PRIVATE)
                    .type(PRIVATE)
                    .build())
            .build());
  }

  @Test
  void sdkAndSpringStatusesAreFoundInsideWrappedFailures() {
    var rest =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.FORBIDDEN,
            PRIVATE,
            org.springframework.http.HttpHeaders.EMPTY,
            PRIVATE.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);
    var reactive =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            401,
            PRIVATE,
            org.springframework.http.HttpHeaders.EMPTY,
            PRIVATE.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);
    var errors =
        List.of(
            sdkFailure(),
            new IllegalStateException(PRIVATE, rest),
            new IllegalStateException(PRIVATE, reactive));
    var expected = List.of(400, 403, 401);
    for (int i = 0; i < errors.size(); i++) {
      var details = ActivityVisionFailure.details(errors.get(i));
      assertThat(details.httpStatus()).isEqualTo(expected.get(i));
      assertThat(details.errorType()).isEqualTo(IllegalStateException.class.getName());
      assertThat(details.causes()).hasSize(2);
      assertThat(details.truncated()).isFalse();
      assertThat(details.toString()).doesNotContain("PRIVATE", "forged_log_entry");
    }
  }

  @Test
  void nonHttpFailuresAndMissingFailureAreExplicit() {
    var details =
        ActivityVisionFailure.details(
            new IllegalStateException(PRIVATE, new java.net.ConnectException(PRIVATE)));
    assertThat(details.httpStatus()).isNull();
    assertThat(details.causes())
        .containsExactly(
            IllegalStateException.class.getName(), java.net.ConnectException.class.getName());
    assertThat(ActivityVisionFailure.details(null).errorType()).isEqualTo("none");
    assertThat(ActivityVisionFailure.details(null).causes()).isEmpty();
  }

  @Test
  void cyclicAndDeepCausesAreBounded() {
    var first = new IllegalStateException(PRIVATE);
    var second = new IllegalArgumentException(PRIVATE, first);
    first.initCause(second);
    var cycle = ActivityVisionFailure.details(first);
    assertThat(cycle.causes()).hasSize(2);
    assertThat(cycle.truncated()).isTrue();
    Throwable deep = new java.io.IOException(PRIVATE);
    for (int i = 0; i < 30; i++) deep = new RuntimeException(PRIVATE, deep);
    var bounded = ActivityVisionFailure.details(deep);
    assertThat(bounded.causes()).hasSize(16);
    assertThat(bounded.truncated()).isTrue();
  }

  @Test
  void pipelineFailureCorrelatesObservationAndCauseWithoutThrowableOrBody() throws Exception {
    var fixture = new ActivityVisionQueueTest.Fixture();
    when(fixture.extractor.extract(any(), any())).thenThrow(sdkFailure());
    var logger = (Logger) LoggerFactory.getLogger(ActivityEvidencePipeline.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      fixture.pipeline.tick();
      fixture.run();
      var record = org.mockito.ArgumentCaptor.forClass(ActivityRecord.class);
      verify(fixture.store).append(record.capture());
      var failure =
          appender.list.stream()
              .filter(e -> e.getFormattedMessage().startsWith("Activity Vision failure:"))
              .findFirst()
              .orElseThrow();
      assertThat(failure.getFormattedMessage())
          .contains(
              "scope=foreground",
              "reason=OTHER",
              "observation_id=" + record.getValue().id(),
              "stage=extract",
              "error_type=java.lang.IllegalStateException",
              "com.openai.errors.BadRequestException",
              "http_status=400",
              "cause_chain_truncated=false");
      assertThat(appender.list)
          .allSatisfy(
              event -> {
                assertThat(event.getFormattedMessage())
                    .doesNotContain("PRIVATE", "forged_log_entry");
                assertThat(event.getThrowableProxy()).isNull();
              });
      assertThat(fixture.pipeline.queueMetrics().failed()).isEqualTo(1);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void extractorRethrowsOriginalFailureAndReportsItsRequestStage() {
    var model = mock(ChatModel.class);
    var error = sdkFailure();
    when(model.call(any(Prompt.class))).thenThrow(error);
    var logger = (Logger) LoggerFactory.getLogger(VisionActivityExtractor.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var extractor =
          new VisionActivityExtractor(() -> model, () -> OpenAiChatOptions.builder().build());
      assertThatThrownBy(
              () ->
                  extractor.extract(
                      ActivityCaptureTest.screen(20),
                      new ForegroundWindow("Firefox", 1, PRIVATE, "1")))
          .isSameAs(error);
      assertThat(appender.list).hasSize(1);
      var event = appender.list.getFirst();
      assertThat(event.getFormattedMessage())
          .contains(
              "status=request_failed",
              "http_status=400",
              "com.openai.errors.BadRequestException",
              "output_chars=0",
              "output_parse_ms=0");
      assertThat(event.getFormattedMessage()).doesNotContain("PRIVATE", "forged_log_entry");
      assertThat(event.getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
