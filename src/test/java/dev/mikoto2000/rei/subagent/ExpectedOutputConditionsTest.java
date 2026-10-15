package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ExpectedOutputConditionsTest {
  final SubAgentResultSchema reviewer =
      SubAgentResultSchema.load(
          Path.of("."), "classpath:/subagents/schemas/reviewer-result.schema.json");

  @Test
  void acceptsPartialLiteralConditionsWithoutDemandingAllRequiredFields() {
    assertDoesNotThrow(() -> ExpectedOutputConditions.validate("{\"unverified\":[]}", reviewer));
    assertDoesNotThrow(
        () ->
            ExpectedOutputConditions.validate(
                "{\"findings\":[{\"severity\":\"HIGH\",\"message\":\"具体的な指摘\",\"location\":\"docs/a.md\"}]}",
                reviewer));
  }

  @Test
  void rejectsSchemaTemplatesUnknownFieldsAndInvalidEnums() {
    for (String value :
        new String[] {
          "not json",
          "{}",
          "[]",
          "{\"type\":\"object\",\"properties\":{}}",
          "{\"summary\":\"string\"}",
          "{\"unverified\":\"string\"}",
          "{\"findings\":[{\"severity\":\"high|medium|low\",\"message\":\"string\",\"location\":\"string\"}]}"
        })
      assertThrows(
          IllegalArgumentException.class, () -> ExpectedOutputConditions.validate(value, reviewer));
  }

  @Test
  void failureSummaryIncludesCodeAndSafeValidationLocations() {
    String result =
        ChatGptSubAgentBackend.failureSummary(
            ChatGptSubAgentBackend.State.FAILED,
            dev.mikoto2000.rei.chatgpt.ChatGptException.Code.VALIDATION_FAILED,
            java.util.List.of(new ValidationError("/subagent/result", "PRIVATE OUTPUT")));
    assertTrue(result.contains("VALIDATION_FAILED"));
    assertTrue(result.contains("/subagent/result"));
    assertFalse(result.contains("PRIVATE OUTPUT"));
    assertTrue(
        ChatGptSubAgentBackend.failureSummary(
                ChatGptSubAgentBackend.State.FAILED,
                dev.mikoto2000.rei.chatgpt.ChatGptException.Code.SEND_LIMIT,
                java.util.List.of())
            .contains("分割"));
  }

  @Test
  void cliEventProjectionIncludesChildIdentityAndFailureReason() {
    var factory = new dev.mikoto2000.rei.event.AgentEventFactory(java.time.Clock.systemUTC());
    var event =
        factory.subAgentLifecycle(
            dev.mikoto2000.rei.event.AgentEventType.SUBAGENT_FAILED,
            "parent",
            "child",
            "reviewer",
            "review",
            "FAILED",
            10,
            "SEND_LIMIT: 資料を分割してください。");
    var dto = dev.mikoto2000.rei.web.WebApiEventDto.from(event, false, "fixture-key");
    assertEquals("child", dto.payload().get("subAgentRunId"));
    assertEquals("parent", dto.payload().get("parentRunId"));
    assertTrue(dto.payload().get("failureReason").toString().contains("SEND_LIMIT"));
  }
}
