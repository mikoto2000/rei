package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.ExecutionProgressPayload;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** WebAPIイベントDtoの結果と状態の契約を検証するテスト。 */
class WebApiEventDtoTest {

  @Test
  void fileBudgetFailuresExposeOnlyFixedCauseAndRecoveryGuidance() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var error =
        new ErrorInformation("PRIVATE_CLASS", "PRIVATE_PATH secret", "file_result_budget_exceeded");
    for (var event :
        java.util.List.of(
            factory.toolFailed("call", "searchAndRead", error), factory.runFailed("run", error))) {
      var dto = WebApiEventDto.from(event, false, "secret");
      assertThat(dto.payload().toString())
          .contains("file_result_budget_exceeded", "出力上限", "baseDir", "再試行")
          .doesNotContain("Operation failed.", "PRIVATE", "secret");
    }
  }

  /** 既知の実行停止理由だけを固定メッセージに変換し、内部の詳細を公開しない。 */
  @Test
  void runBudgetAndStagnationStopsExposeSafeSpecificDiagnostics() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var cases =
        java.util.Map.of(
            "ReplanBudgetExceeded", "replan_budget_exceeded",
            "Stagnated", "stagnated",
            "LlmCallBudgetExceeded", "llm_call_budget_exceeded");
    cases.forEach(
        (errorType, publicType) -> {
          var error = new ErrorInformation(errorType, "private credential=secret", "private-code");
          var dto = WebApiEventDto.from(factory.runFailed("run", error), false, "");
          assertThat(dto.payload().get("error").toString())
              .contains("type=" + publicType)
              .doesNotContain("Operation failed.", "private", "secret");
          // 同名のツール例外は実行全体の停止理由として扱わない。
          assertThat(
                  WebApiEventDto.from(factory.toolFailed("call", "read", error), false, "")
                      .payload()
                      .get("error")
                      .toString())
              .contains("type=operation_failed");
        });
    var exhausted =
        WebApiEventDto.from(
            factory.runFailed(
                "run", new ErrorInformation("ReplanBudgetExceeded", "private", "private")),
            false,
            "");
    assertThat(exhausted.payload().get("error").toString()).contains("実行全体", "リセット");
  }

  /** 公開受付から終了までの管理対応付け一覧について、共通の通知属性を保持することを確認する。 */
  @Test
  void publicLifecycleMappingsPreserveEnvelopeAndOnlyApprovedPayloadFields() {
    var f =
        new AgentEventFactory(
            Clock.fixed(java.time.Instant.parse("2026-09-17T01:00:00Z"), java.time.ZoneOffset.UTC));
    var error = new ErrorInformation("InternalException", "private", "code");
    var samples =
        java.util.List.of(
            f.runStarted("run", "private", null),
            f.runCompleted("run", 20),
            f.runFailed("run", error),
            f.runCancelled("run", error),
            f.messageStarted("m", "assistant"),
            f.messageDelta("m", "hello"),
            f.messageCompleted("m", "assistant", "hello"),
            f.toolStarted("call", "read", "private"),
            f.toolCompleted("call", "read", 20, "private", 1, 3L, 2, 4),
            f.toolFailed("call", "read", error),
            f.llmRequestStarted("run", "q", "chat"),
            f.llmResponseFirstToken("run", "q", 10),
            f.llmResponseCompleted("run", "q", 20),
            f.llmRequestFailed("run", "q", 20, new IllegalStateException("private")),
            f.skillSelectionStarted("sel"),
            f.skillSelectionCompleted(
                "sel",
                java.util.List.of("coding"),
                java.util.List.of(),
                java.util.List.of("private")),
            f.skillSelectionFailed("sel", error),
            f.skillRoutingStarted("run", "route", 2, 1),
            f.skillRoutingCompleted(
                "run",
                "route",
                20,
                2,
                "coding",
                1,
                10L,
                2L,
                3L,
                java.util.List.of("coding"),
                java.util.List.of(),
                java.util.List.of("private")),
            f.skillRoutingFailed("run", "route", 20, 2, 1, error),
            f.workingSetItemAdded("file", "file", "Foo.java", "Foo.java", "private"),
            f.workingSetItemRemoved("file", "private"),
            f.workingSetSearchStarted("search", "private", "private", 2),
            f.workingSetSearchCompleted("search", 20, 2, 2, 1, 0, 2, 3),
            f.workingSetContextInjected(2, 300),
            f.thinkingStarted("think"),
            f.thinkingDelta("think", "considering"),
            f.thinkingCompleted("think", "considering"));
    long sequence = 0;
    for (var sample : samples) {
      var e = sample.withOwnership(RunRegistryTest.context("run"));
      e =
          new AgentEvent(
              e.id(),
              ++sequence,
              e.timestamp(),
              e.type(),
              e.version(),
              e.sessionId(),
              e.turnId(),
              e.runId(),
              e.correlationId(),
              e.parentEventId(),
              e.payload(),
              e.projectId());
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
      assertThat(dto.payload().toString())
          .doesNotContain("private", "InternalException", "errorType");
    }
    for (var type :
        java.util.List.of(
            AgentEventType.PROGRESS_DETECTED,
            AgentEventType.STAGNATION_UPDATED,
            AgentEventType.STAGNATION_DETECTED,
            AgentEventType.STAGNATION_REPLAN_REQUESTED,
            AgentEventType.STAGNATION_RECOVERED,
            AgentEventType.STAGNATION_STOPPED)) {
      assertThat(
              WebApiEventMapper.from(
                      f.executionProgress(
                          type, "run", new ExecutionProgressPayload(null, 1, 4, 1, 2, "private")),
                      false,
                      "")
                  .payload())
          .containsOnlyKeys(
              "consecutiveNoProgressIterations",
              "threshold",
              "stagnationReplanCount",
              "maxStagnationReplans");
    }
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void activityAggregatesArePublicWithoutInternalEvidenceOrSearchQuery() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var progress =
        WebApiEventDto.from(
            factory.executionProgress(
                AgentEventType.STAGNATION_UPDATED,
                "run",
                new ExecutionProgressPayload(null, 2, 4, 1, 3, "internal reason")),
            false,
            "");
    assertThat(progress.payload())
        .containsEntry("consecutiveNoProgressIterations", 2)
        .containsEntry("threshold", 4)
        .containsEntry("stagnationReplanCount", 1)
        .containsEntry("maxStagnationReplans", 3)
        .doesNotContainKeys("evidence", "reason");
    var search =
        WebApiEventDto.from(
            factory.workingSetSearchCompleted("search", 15, 8, 5, 2, 1, 3, 5), false, "");
    assertThat(search.payload())
        .containsEntry("hitCount", 8)
        .containsEntry("selectedCount", 2)
        .containsEntry("alreadyPresentCount", 1)
        .containsEntry("workingSetSizeAfter", 5);
    var context = WebApiEventDto.from(factory.workingSetContextInjected(5, 1200), false, "");
    assertThat(context.payload())
        .containsEntry("itemCount", 5)
        .containsEntry("contextCharacters", 1200);
    var candidates =
        WebApiEventDto.from(
            factory.skillCandidatesEvaluated(
                "run", "routing", 12, 20, "coding", true, true, true, true, java.util.List.of()),
            false,
            "");
    assertThat(candidates.correlationId()).isEqualTo("routing");
    assertThat(candidates.payload())
        .containsEntry("totalSkillCount", 12)
        .containsEntry("actualSelectedSkill", "coding")
        .doesNotContainKey("topCandidates");
    assertThat(
            WebApiEventDto.from(factory.thinkingDelta("thinking", "considering"), false, "")
                .payload())
        .containsEntry("thinkingId", "thinking")
        .containsEntry("delta", "considering");
  }

  /** Web APIによる操作で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void thinkingTextReachesClientsWithCredentialRedaction() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    assertThat(WebApiEventDto.from(factory.thinkingStarted("t"), false, "api-secret").payload())
        .containsOnlyKeys("thinkingId");
    String text = "検討中 api-secret Bearer bearer-value password=hidden";
    var delta = WebApiEventDto.from(factory.thinkingDelta("t", text), false, "api-secret");
    var completed = WebApiEventDto.from(factory.thinkingCompleted("t", text), false, "api-secret");
    assertThat(delta.payload()).containsOnlyKeys("thinkingId", "delta");
    assertThat(completed.payload()).containsOnlyKeys("thinkingId", "text");
    assertThat(delta.payload().get("delta")).isEqualTo(completed.payload().get("text"));
    assertThat(delta.payload().get("delta").toString())
        .contains("検討中", "[REDACTED]")
        .doesNotContain("api-secret", "bearer-value", "hidden");
  }

  /** 関連付けを維持することを確認する。 */
  @Test
  void toolBoundaryOmitsArbitraryArgumentsAndResultsButKeepsCorrelationAndTiming() {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var started =
        WebApiEventDto.from(
            factory.toolStarted(
                "call", "readFile", "{\"env\":{\"PRIVATE_VALUE\":\"hidden\"}}", "hidden"),
            false,
            "");
    assertThat(started.correlationId()).isEqualTo("call");
    assertThat(started.payload())
        .containsEntry("toolCallId", "call")
        .containsEntry("toolName", "readFile")
        .doesNotContainKeys("argumentsSummary", "summary");
    var completed =
        WebApiEventDto.from(
            factory.toolCompleted("call", "readFile", 18, "file secret"), false, "");
    assertThat(completed.payload())
        .containsEntry("duration", 18L)
        .doesNotContainKey("resultSummary");
  }

  /** Web APIによる操作で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void stableEnvelopeAndMessagePayloadDoNotExposeInternalClassesOrCredentials() throws Exception {
    var event =
        new AgentEventFactory(Clock.systemUTC())
            .messageDelta("message", "hello test-secret")
            .withOwnership(RunRegistryTest.context("run"));
    var dto = WebApiEventDto.from(event, false, "test-secret");
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
    assertThat(dto.type()).isEqualTo("message.delta");
    assertThat(dto.runId()).isEqualTo("run");
    assertThat(dto.sessionId()).isEqualTo("session");
    assertThat(dto.turnId()).isEqualTo("run");
    assertThat(dto.payload())
        .containsEntry("messageId", "message")
        .containsEntry("delta", "hello [REDACTED]");
    assertThat(json).doesNotContain("MessageDeltaPayload", "test-secret", "@class");
  }

  /** Web APIによる操作の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledStateNormalizesLegacyFailureAndProjectsExternalErrorFields() {
    var event =
        new AgentEventFactory(Clock.systemUTC())
            .runFailed("run", new ErrorInformation("error", "bad", "internal-code"));
    var dto = WebApiEventDto.from(event, true, "");
    assertThat(dto.type()).isEqualTo("agent.run.cancelled");
    assertThat(dto.payload().toString())
        .contains("type=operation_failed", "message=Operation failed.")
        .doesNotContain("internal-code", "errorType", "bad");
  }

  /** Web APIによる操作で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void errorsAreGenericAndCredentialRedactionCoversBearerBasicAndEnvelope() throws Exception {
    var factory = new AgentEventFactory(Clock.systemUTC());
    var error =
        WebApiEventDto.from(
            factory.toolFailed(
                "call",
                "read",
                new ErrorInformation(
                    "InternalException",
                    "C:\\private\\key.txt\n at internal.Frame(method:12) hidden",
                    "hidden")),
            false,
            "hidden");
    assertThat(error.payload().toString())
        .contains("Operation failed.")
        .doesNotContain("InternalException", "private", "Frame", "hidden");
    var event =
        factory
            .messageDelta(
                "m", "Bearer bearer-value Basic basic-value password=pass sk-abcdefghijklmnop")
            .withContext("api-secret", "turn");
    var dto = WebApiEventDto.from(event, false, "api-secret");
    var json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
    assertThat(json)
        .doesNotContain(
            "bearer-value", "basic-value", "pass ", "sk-abcdefghijklmnop", "api-secret");
  }
}
