package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.web.WebApiEventMapper;

class CompressionEventTest {
  @Test void hardLimitRemainsExplicitAtWebBoundaryWithoutExposingInternalText() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var dto = WebApiEventMapper.from(factory.runFailed("run", new ErrorInformation(
        "ContextHardLimit", "internal text should not escape", "context_hard_limit")), false, "secret");
    assertThat(dto.payload().get("error").toString()).contains("context_hard_limit", "CONTEXT_HARD_LIMIT")
        .doesNotContain("internal text");
  }
  @Test void exposesCompressionMetricsThroughExistingWebBoundary() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    for (var type : List.of(AgentEventType.CONTEXT_COMPRESSION_STARTED,
        AgentEventType.CONTEXT_COMPRESSION_COMPLETED, AgentEventType.CONTEXT_COMPRESSION_FAILED)) {
      var dto = WebApiEventMapper.from(factory.contextCompression(type,
          new ContextCompressionPayload(1000, 200, 10, 30, "compression_unavailable")), false, "secret");
      assertThat(dto.type()).isEqualTo(type.value());
      assertThat(dto.payload()).containsEntry("beforeEstimatedTokens", 1000L)
          .containsEntry("afterEstimatedTokens", 200L).containsEntry("summaryThroughSequence", 30L);
    }
  }
}
