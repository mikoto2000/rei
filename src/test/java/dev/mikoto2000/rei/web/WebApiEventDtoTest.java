package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebApiEventDtoTest {
  @Test void stableEnvelopeAndMessagePayloadDoNotExposeInternalClassesOrCredentials() throws Exception {
    var event = new AgentEventFactory(Clock.systemUTC()).messageDelta("message", "hello test-secret")
        .withOwnership(RunRegistryTest.context("run"));
    var dto = WebApiEventDto.from(event, false, "test-secret");
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
    assertThat(dto.type()).isEqualTo("message.delta");
    assertThat(dto.runId()).isEqualTo("run");
    assertThat(dto.sessionId()).isEqualTo("session");
    assertThat(dto.turnId()).isEqualTo("run");
    assertThat(dto.payload()).containsEntry("messageId", "message").containsEntry("delta", "hello [REDACTED]");
    assertThat(json).doesNotContain("MessageDeltaPayload", "test-secret", "@class");
  }
  @Test void cancelledStateNormalizesLegacyFailureAndProjectsExternalErrorFields() {
    var event = new AgentEventFactory(Clock.systemUTC()).runFailed("run", new ErrorInformation("error", "bad", "internal-code"));
    var dto = WebApiEventDto.from(event, true, "");
    assertThat(dto.type()).isEqualTo("agent.run.cancelled");
    assertThat(dto.payload().toString()).contains("type=error", "message=bad").doesNotContain("internal-code", "errorType");
  }
}
