package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;

/** Actual SDK wire fixtures: exact Plan usage codes, generic statuses, and missing usage differ. */
class PlanUsageFailureWireTest {
  static Stream<Arguments> usageFailures() {
    return Stream.of(
            "subscription_sharing_usage_limit_exceeded", "subscription_sharing_usage_unavailable")
        .flatMap(code ->
            Stream.of("http", "error", "delta-error", "failed")
                .map(shape -> Arguments.of(code, shape)));
  }

  @ParameterizedTest
  @MethodSource("usageFailures")
  void exactUsageFailuresRetainTheirSafeMeaningBeforeAndAfterDeltas(String code, String shape)
      throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      if (shape.equals("http")) {
        fixture.status = code.endsWith("exceeded") ? 429 : 503;
        fixture.mediaType = "application/json";
        fixture.reply =
            "{\"error\":{\"code\":\"" + code + "\",\"message\":\"PRIVATE provider body\"}}";
      } else if (shape.equals("failed")) {
        fixture.reply =
            PlanResponsesRequestTest.Fixture.event(
                "response.failed",
                "\"response\":{\"id\":\"resp_fixture\",\"object\":\"response\",\"created_at\":0,\"status\":\"failed\","
                    + "\"model\":\"fixture-model\",\"output\":[],\"error\":{\"code\":\""
                    + code
                    + "\",\"message\":\"PRIVATE body\"},"
                    + "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}");
      } else {
        fixture.reply =
            (shape.equals("delta-error")
                    ? PlanResponsesRequestTest.Fixture.event(
                        "response.output_text.delta",
                        "\"item_id\":\"msg_fixture\",\"output_index\":0,\"content_index\":0,\"delta\":\"partial\"")
                    : "")
                + PlanResponsesRequestTest.Fixture.event(
                    "error",
                    "\"code\":\"" + code + "\",\"message\":\"PRIVATE body\",\"param\":null");
      }
      var model = ResponsesAdapter.createForRun(fixture.pin, fixture.options(), fixture.client);
      assertThatThrownBy(() ->
              model.stream(new Prompt("fixture", fixture.options()))
                  .collectList()
                  .block(Duration.ofSeconds(5)))
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              error -> {
                assertThat(error.diagnosticCode())
                    .isEqualTo(
                        code.endsWith("exceeded")
                            ? "chatgpt_usage_limit"
                            : "chatgpt_usage_unavailable");
                assertThat(error.retryAllowed()).isFalse();
                assertThat(error.confirmedAppUsageLimit()).isEqualTo(code.endsWith("exceeded"));
                assertThat(error.getCause()).isNull();
                assertThat(error.getMessage()).doesNotContain("PRIVATE");
                assertThat(error.diagnosticMessage()).doesNotContain("PRIVATE");
                if (shape.equals("failed")) assertThat(error.usage().total()).isEqualTo(5L);
                else assertThat(error.usage()).isNull();
              });
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 503})
  void statusWithoutTheExactCodeCannotClaimAnAppUsageLimit(int status) throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      fixture.status = status;
      fixture.mediaType = "application/json";
      fixture.reply = "{\"detail\":\"PRIVATE generic admission failure\"}";
      var model = ResponsesAdapter.createForRun(fixture.pin, fixture.options(), fixture.client);
      assertThatThrownBy(() -> model.call(new Prompt("fixture", fixture.options())))
          .isInstanceOfSatisfying(
              ResponsesTransportException.class,
              error -> {
                assertThat(error.diagnosticCode())
                    .isEqualTo(status == 429 ? "rate_limited" : "provider_unavailable");
                assertThat(error.retryAllowed()).isFalse();
                assertThat(error.confirmedAppUsageLimit()).isFalse();
                assertThat(error.getCause()).isNull();
              });
      assertThat(fixture.requests).hasSize(1);
    }
  }

  @Test
  void missingUsageRemainsUnknownAfterTheSdkAndBlockingAggregation() throws Exception {
    try (var fixture = new PlanResponsesRequestTest.Fixture()) {
      fixture.reply =
          fixture.reply.replace(
              "{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}", "null");
      var model = ResponsesAdapter.createForRun(fixture.pin, fixture.options(), fixture.client);
      var response = model.call(new Prompt("fixture", fixture.options()));
      assertThat(response.getResult().getOutput().getText()).isEqualTo("done");
      var usage = ResponsesTransportException.usage(response);
      assertThat(usage).isNotNull();
      assertThat(usage.input()).isNull();
      assertThat(usage.output()).isNull();
      assertThat(usage.total()).isNull();
      assertThat(usage.cached()).isNull();
      assertThat(usage.reasoning()).isNull();
      assertThat(fixture.requests).hasSize(1);
    }
  }
}
