package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.event.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebApiEventDtoTest {
  @Test void publicLifecycleMappingsPreserveEnvelopeAndOnlyApprovedPayloadFields() {
    var f = new AgentEventFactory(Clock.fixed(java.time.Instant.parse("2026-09-17T01:00:00Z"), java.time.ZoneOffset.UTC));
    var error = new ErrorInformation("InternalException", "private", "code");
    var samples = java.util.List.of(
        f.runStarted("run", "private", null), f.runCompleted("run", 20), f.runFailed("run", error), f.runCancelled("run", error),
        f.messageStarted("m", "assistant"), f.messageDelta("m", "hello"), f.messageCompleted("m", "assistant", "hello"),
        f.toolStarted("call", "read", "private"), f.toolCompleted("call", "read", 20, "private", 1, 3L, 2, 4), f.toolFailed("call", "read", error),
        f.llmRequestStarted("run", "q", "chat"), f.llmResponseFirstToken("run", "q", 10), f.llmResponseCompleted("run", "q", 20),
        f.llmRequestFailed("run", "q", 20, new IllegalStateException("private")),
        f.skillSelectionStarted("sel"), f.skillSelectionCompleted("sel", java.util.List.of("coding"), java.util.List.of(), java.util.List.of("private")),
        f.skillSelectionFailed("sel", error), f.skillRoutingStarted("run", "route", 2, 1),
        f.skillRoutingCompleted("run", "route", 20, 2, "coding", 1, 10L, 2L, 3L, java.util.List.of("coding"), java.util.List.of(), java.util.List.of("private")),
        f.skillRoutingFailed("run", "route", 20, 2, 1, error),
        f.workingSetItemAdded("file", "file", "Foo.java", "Foo.java", "private"), f.workingSetItemRemoved("file", "private"),
        f.workingSetSearchStarted("search", "private", "private", 2), f.workingSetSearchCompleted("search", 20, 2, 2, 1, 0, 2, 3),
        f.workingSetContextInjected(2, 300), f.thinkingStarted("think"), f.thinkingDelta("think", "private"), f.thinkingCompleted("think", "private"));
    long sequence = 0;
    for (var sample : samples) {
      var e = sample.withOwnership(RunRegistryTest.context("run"));
      e = new AgentEvent(e.id(), ++sequence, e.timestamp(), e.type(), e.version(), e.sessionId(), e.turnId(), e.runId(),
          e.correlationId(), e.parentEventId(), e.payload(), e.projectId());
      var dto = WebApiEventMapper.from(e, false, "");
      assertThat(dto.type()).isEqualTo(e.type().value());
      assertThat(dto.sequence()).isEqualTo(sequence);
      assertThat(dto.timestamp()).isEqualTo("2026-09-17T01:00:00Z");
      assertThat(dto.runId()).isEqualTo("run");
      assertThat(dto.sessionId()).isEqualTo(e.sessionId());
      assertThat(dto.turnId()).isEqualTo(e.turnId());
      assertThat(dto.projectId()).isEqualTo(e.projectId());
      assertThat(dto.correlationId()).isEqualTo(e.correlationId());
      assertThat(dto.payload()).isNotEmpty();
      assertThat(dto.payload().toString()).doesNotContain("private", "InternalException", "errorType");
    }
    for (var type : java.util.List.of(AgentEventType.PROGRESS_DETECTED, AgentEventType.STAGNATION_UPDATED,
        AgentEventType.STAGNATION_DETECTED, AgentEventType.STAGNATION_REPLAN_REQUESTED,
        AgentEventType.STAGNATION_RECOVERED, AgentEventType.STAGNATION_STOPPED)) {
      assertThat(WebApiEventMapper.from(f.executionProgress(type, "run", new ExecutionProgressPayload(null, 1, 4, 1, 2, "private")), false, "").payload())
          .containsOnlyKeys("consecutiveNoProgressIterations", "threshold", "stagnationReplanCount", "maxStagnationReplans");
    }
  }
  @Test void activityAggregatesArePublicWithoutInternalEvidenceOrSearchQuery() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var progress = WebApiEventDto.from(factory.executionProgress(AgentEventType.STAGNATION_UPDATED,
        "run", new ExecutionProgressPayload(null, 2, 4, 1, 3, "internal reason")), false, "");
    assertThat(progress.payload()).containsEntry("consecutiveNoProgressIterations", 2)
        .containsEntry("threshold", 4).containsEntry("stagnationReplanCount", 1)
        .containsEntry("maxStagnationReplans", 3).doesNotContainKeys("evidence", "reason");
    var search = WebApiEventDto.from(factory.workingSetSearchCompleted("search", 15, 8, 5, 2, 1, 3, 5), false, "");
    assertThat(search.payload()).containsEntry("hitCount", 8).containsEntry("selectedCount", 2)
        .containsEntry("alreadyPresentCount", 1).containsEntry("workingSetSizeAfter", 5);
    var context = WebApiEventDto.from(factory.workingSetContextInjected(5, 1200), false, "");
    assertThat(context.payload()).containsEntry("itemCount", 5).containsEntry("contextCharacters", 1200);
    var candidates = WebApiEventDto.from(factory.skillCandidatesEvaluated("run", "routing", 12, 20,
        "coding", true, true, true, true, java.util.List.of()), false, "");
    assertThat(candidates.correlationId()).isEqualTo("routing");
    assertThat(candidates.payload()).containsEntry("totalSkillCount", 12)
        .containsEntry("actualSelectedSkill", "coding").doesNotContainKey("topCandidates");
    assertThat(WebApiEventDto.from(factory.thinkingDelta("thinking", "private reasoning"), false, "").payload())
        .containsEntry("thinkingId", "thinking").doesNotContainKey("delta");
  }
  @Test void toolBoundaryOmitsArbitraryArgumentsAndResultsButKeepsCorrelationAndTiming() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var started = WebApiEventDto.from(factory.toolStarted("call", "readFile",
        "{\"env\":{\"PRIVATE_VALUE\":\"hidden\"}}", "hidden"), false, "");
    assertThat(started.correlationId()).isEqualTo("call");
    assertThat(started.payload()).containsEntry("toolCallId", "call").containsEntry("toolName", "readFile")
        .doesNotContainKeys("argumentsSummary", "summary");
    var completed = WebApiEventDto.from(factory.toolCompleted("call", "readFile", 18, "file secret"), false, "");
    assertThat(completed.payload()).containsEntry("duration", 18L).doesNotContainKey("resultSummary");
  }
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
    assertThat(dto.payload().toString()).contains("type=operation_failed", "message=Operation failed.")
        .doesNotContain("internal-code", "errorType", "bad");
  }
  @Test void errorsAreGenericAndCredentialRedactionCoversBearerBasicAndEnvelope() throws Exception {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var error = WebApiEventDto.from(factory.toolFailed("call", "read", new ErrorInformation(
        "InternalException", "C:\\private\\key.txt\n at internal.Frame(method:12) hidden", "hidden")), false, "hidden");
    assertThat(error.payload().toString()).contains("Operation failed.")
        .doesNotContain("InternalException", "private", "Frame", "hidden");
    var event = factory.messageDelta("m", "Bearer bearer-value Basic basic-value password=pass sk-abcdefghijklmnop")
        .withContext("api-secret", "turn");
    var dto = WebApiEventDto.from(event, false, "api-secret");
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
    assertThat(json).doesNotContain("bearer-value", "basic-value", "pass ", "sk-abcdefghijklmnop", "api-secret");
  }
}
