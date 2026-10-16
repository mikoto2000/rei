package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ContextCompressionPayload;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.web.WebApiEventMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompressionEventTest {
  @Test
  void hardLimitRemainsExplicitAtWebBoundaryWithoutExposingInternalText() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var dto =
        WebApiEventMapper.from(
            factory.runFailed(
                "run",
                new ErrorInformation(
                    "ContextHardLimit", "internal text should not escape", "context_hard_limit")),
            false,
            "secret");
    assertThat(dto.payload().get("error").toString())
        .contains("context_hard_limit", "CONTEXT_HARD_LIMIT")
        .doesNotContain("internal text");
  }

  @Test
  void exposesCompressionMetricsThroughExistingWebBoundary() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    for (var type :
        List.of(
            AgentEventType.CONTEXT_COMPRESSION_STARTED,
            AgentEventType.CONTEXT_COMPRESSION_COMPLETED,
            AgentEventType.CONTEXT_COMPRESSION_FAILED,
            AgentEventType.CONTEXT_COMPRESSION_SKIPPED)) {
      var dto =
          WebApiEventMapper.from(
              factory.contextCompression(
                  type,
                  new ContextCompressionPayload(1000, 200, 10, 30, "compression_unavailable")),
              false,
              "secret");
      assertThat(dto.type()).isEqualTo(type.value());
      assertThat(dto.payload())
          .containsEntry("beforeEstimatedTokens", 1000L)
          .containsEntry("afterEstimatedTokens", 200L)
          .containsEntry("summaryThroughSequence", 30L);
    }
  }

  @Test
  void typedCompressionDetailsAreExposedAndRedacted() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var dto =
        WebApiEventMapper.from(
            factory.contextCompression(
                AgentEventType.CONTEXT_COMPRESSION_SKIPPED,
                new ContextCompressionPayload(
                    1000,
                    1000,
                    10,
                    30,
                    "retry_cooldown",
                    "retryAfterSeconds=120 token=hidden; apiKey=api-secret")),
            false,
            "api-secret");
    assertThat(dto.payload()).containsEntry("reason", "retry_cooldown");
    assertThat(dto.payload().get("details").toString())
        .contains("retryAfterSeconds=120", "[REDACTED]")
        .doesNotContain("hidden", "api-secret");
  }
}
