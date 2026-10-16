package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AgentRunFailedPayload;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ChatExecutionFailureDiagnosticsTest {
  private final AgentRunContext owner =
      new AgentRunContext("run", "session", Path.of("."), "project");
  private final List<AgentEvent> events = new ArrayList<>();
  private final AtomicInteger modelCalls = new AtomicInteger();

  private ChatExecutionService service(Throwable failure) {
    ChatModel model =
        new ChatModel() {
          public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build();
          }

          public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException();
          }

          public Flux<ChatResponse> stream(Prompt prompt) {
            modelCalls.incrementAndGet();
            return Flux.error(failure);
          }
        };
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenReturn("test");
    return new ChatExecutionService(
        ChatClient.builder(model).build(),
        holder,
        new CommandCancellationService(),
        Optional.empty(),
        new AgentEventFactory(Clock.systemUTC()),
        events::add);
  }

  private List<AgentEvent> terminals() {
    return events.stream()
        .filter(
            e ->
                e.type() == AgentEventType.AGENT_RUN_FAILED
                    || e.type() == AgentEventType.AGENT_RUN_COMPLETED)
        .toList();
  }

  @ParameterizedTest
  @CsvSource({
    "401,authentication_failed",
    "403,permission_denied",
    "413,provider_request_too_large",
    "429,rate_limited",
    "503,provider_unavailable"
  })
  void wrappedHttpFailureHasOneSpecificTerminalAndDoesNotRetryOrExposeProviderContent(
      int status, String code) {
    var headers = new org.springframework.http.HttpHeaders();
    headers.set("Authorization", "Bearer PRIVATE_HEADER");
    var failure =
        org.springframework.web.reactive.function.client.WebClientResponseException.create(
            status,
            "PRIVATE_STATUS",
            headers,
            "PRIVATE_PROVIDER_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            java.nio.charset.StandardCharsets.UTF_8);
    var result =
        service(new IllegalStateException("PRIVATE_WRAPPER", failure))
            .execute(owner, "work", new UserInterventionQueue());
    assertThat(result.success()).isFalse();
    assertThat(result.stopCode()).isEqualTo(code);
    assertThat(result.errorMessage())
        .contains("HTTP " + status)
        .doesNotContain("PRIVATE", "chat run failed");
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
    var error = ((AgentRunFailedPayload) terminals().getFirst().payload()).error();
    assertThat(error.code()).isEqualTo(code);
    assertThat(error.message()).isEqualTo(result.errorMessage());
    assertThat(events.toString()).doesNotContain("PRIVATE");
  }

  @Test
  void providerFailureLogsOnlyFixedClassifiedDetailsWithoutBodiesOrStackTraces() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(ChatExecutionService.class);
    var capture =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    capture.start();
    logger.addAppender(capture);
    try {
      var provider =
          org.springframework.web.reactive.function.client.WebClientResponseException.create(
              429,
              "PRIVATE_STATUS",
              org.springframework.http.HttpHeaders.EMPTY,
              "PRIVATE_PROVIDER_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8),
              java.nio.charset.StandardCharsets.UTF_8);
      service(new RuntimeException("PRIVATE_WRAPPER", provider))
          .execute(owner, "work", new UserInterventionQueue());
      var failures =
          capture.list.stream()
              .filter(e -> e.getFormattedMessage().startsWith("Chat response failed"))
              .toList();
      assertThat(failures).hasSize(1);
      assertThat(failures.getFirst().getFormattedMessage())
          .contains("rate_limited", "HTTP 429")
          .doesNotContain("PRIVATE", "responseBody");
      assertThat(failures.getFirst().getThrowableProxy()).isNull();
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  @Test
  void wrappedSdkContextFailureUsesItsSafeCodeAndTimeoutKeepsOutcomeVerificationGuidance() {
    var sdk =
        com.openai.errors.BadRequestException.builder()
            .headers(com.openai.core.http.Headers.builder().build())
            .error(
                com.openai.models.ErrorObject.builder()
                    .code("context_length_exceeded")
                    .message("PRIVATE_BODY")
                    .param("PRIVATE_PARAM")
                    .type("PRIVATE_TYPE")
                    .build())
            .build();
    var result =
        service(new RuntimeException("PRIVATE_OUTER", sdk))
            .execute(owner, "work", new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("provider_context_limit");
    assertThat(result.errorMessage()).doesNotContain("PRIVATE");
    events.clear();
    modelCalls.set(0);
    result =
        service(
                new RuntimeException(
                    "PRIVATE_OUTER", new java.net.SocketTimeoutException("PRIVATE_URL")))
            .execute(
                new AgentRunContext("run2", "session2", Path.of("."), "project"),
                "work",
                new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("timeout");
    assertThat(result.errorMessage()).contains("実行結果を確認").doesNotContain("PRIVATE");
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
  }

  @Test
  void cyclicGenericCauseTerminatesAndNeverEchoesItsMessages() {
    var outer = new RuntimeException("PRIVATE_OUTER");
    var inner = new RuntimeException("PRIVATE_INNER", outer);
    outer.initCause(inner);
    var result = service(outer).execute(owner, "work", new UserInterventionQueue());
    assertThat(result.stopCode()).isEqualTo("invalid_state");
    assertThat(result.errorMessage()).doesNotContain("PRIVATE");
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
  }

  @Test
  void budgetsAndPartialReviewRemainControlStopsAndCancellationRemainsCancelled() {
    var budget =
        service(
                new RuntimeException(
                    "PRIVATE",
                    new ExecutionStoppedException(
                        ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED)))
            .execute(owner, "work", new UserInterventionQueue());
    assertThat(budget.stopCode()).isEqualTo("llm_call_budget_exceeded");
    assertThat(modelCalls).hasValue(1);
    events.clear();
    modelCalls.set(0);
    var review =
        service(new ExecutionStoppedException(ExecutionStoppedException.Reason.REVIEW_PARTIAL))
            .execute(
                new AgentRunContext("review", "review-session", Path.of("."), "project"),
                "work",
                new UserInterventionQueue());
    assertThat(review.stopCode()).isEqualTo("review_partial");
    assertThat(review.errorMessage()).contains("未完了");
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
    events.clear();
    modelCalls.set(0);
    var cancelled =
        service(
                new RuntimeException(
                    "PRIVATE", new java.util.concurrent.CancellationException("PRIVATE_CANCEL")))
            .execute(
                new AgentRunContext("cancel", "cancel-session", Path.of("."), "project"),
                "work",
                new UserInterventionQueue());
    assertThat(cancelled.status()).isEqualTo(ChatExecutionResult.Status.CANCELLED);
    assertThat(modelCalls).hasValue(1);
    assertThat(terminals()).hasSize(1);
    assertThat(events.toString()).doesNotContain("PRIVATE");
  }
}
