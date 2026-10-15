package dev.mikoto2000.rei.chatgpt;

import static org.assertj.core.api.Assertions.*;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatGptDelegationSchemaTest {
  private static final String REQUEST =
      """
      {"type":"object","properties":{"agent":{"type":"string"},"task":{"type":"string"},
      "backendId":{"type":"string"},"modelId":{"type":"string"}},"required":["agent","task"]}
      """;
  private static final PlanDelegationProperties AUTOMATIC =
      new PlanDelegationProperties(true, Set.of("default"), Set.of("gpt-6.1-sol", "gpt-6-astra"));
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private boolean valid(String schema, String arguments) {
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(JSON.readTree(schema))
        .validate(JSON.readTree(arguments))
        .isEmpty();
  }

  @Test
  void conditionalChoicesRejectWrongOrMissingChatGptModelsAndPreserveOtherBackends() {
    String schema = ChatGptDelegationSchema.constrain("delegateTask", REQUEST, AUTOMATIC);
    assertThat(
            valid(
                schema,
                "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"gpt-6.1-sol\"}"))
        .isTrue();
    assertThat(
            valid(
                schema,
                "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"GPT-6.1-Sol\"}"))
        .isFalse();
    assertThat(
            valid(
                schema,
                "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\"}"))
        .isFalse();
    assertThat(valid(schema, "{\"agent\":\"reviewer\",\"task\":\"review\"}")).isTrue();
    assertThat(
            valid(schema, "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"local\"}"))
        .isTrue();
    assertThat(
            valid(
                schema,
                "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"codex-app-server\",\"modelId\":\"codex-specific-model\"}"))
        .isTrue();
    // Definition guidance is never a grant. Runtime validation keeps refusing unlisted models.
    assertThatThrownBy(() -> AUTOMATIC.check("default", "GPT-6.1-Sol"))
        .isInstanceOfSatisfying(
            ChatGptException.class,
            failure ->
                assertThat(failure.permissionReason())
                    .isEqualTo(ChatGptException.PermissionReason.MODEL_NOT_ALLOWED));
  }

  @Test
  void referencedBatchItemsApplyTheConditionIndependentlyWithoutChangingTheirReference() {
    String baseline =
        "{\"$defs\":{\"Request\":"
            + REQUEST
            + "},\"type\":\"object\",\"properties\":{\"requests\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/$defs/Request\"}}}}";
    String schema = ChatGptDelegationSchema.constrain("delegateTasks", baseline, AUTOMATIC);
    assertThat(JSON.readTree(schema).at("/properties/requests/items/allOf/0/$ref").asString())
        .isEqualTo("#/$defs/Request");
    assertThat(
            valid(
                schema,
                "{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\"},{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"codex-app-server\",\"modelId\":\"other\"},{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"gpt-6-astra\"}]}"))
        .isTrue();
    assertThat(
            valid(
                schema,
                "{\"requests\":[{\"agent\":\"reviewer\",\"task\":\"review\"},{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"modelId\":\"invented\"}]}"))
        .isFalse();
  }

  @Test
  void unrelatedDisabledEmptyAndUnusableSchemasStayUnchanged() {
    assertThat(ChatGptDelegationSchema.constrain("runCommand", REQUEST, AUTOMATIC))
        .isEqualTo(REQUEST);
    assertThat(ChatGptDelegationSchema.constrain("delegateTask", REQUEST, null)).isEqualTo(REQUEST);
    assertThat(
            ChatGptDelegationSchema.constrain(
                "delegateTask",
                REQUEST,
                new PlanDelegationProperties(false, Set.of("default"), Set.of("model"))))
        .isEqualTo(REQUEST);
    assertThat(
            ChatGptDelegationSchema.constrain(
                "delegateTask",
                REQUEST,
                new PlanDelegationProperties(true, Set.of("default"), Set.of())))
        .isEqualTo(REQUEST);
    assertThat(ChatGptDelegationSchema.constrain("delegateTask", "{broken", AUTOMATIC))
        .isEqualTo("{broken");
  }

  @Test
  void oversizedOrUnsafeConfiguredModelsSkipEnumWithoutExcludingAnActuallyAllowedModel() {
    var many =
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(index -> "model-" + index)
            .collect(java.util.stream.Collectors.toSet());
    var configured = new PlanDelegationProperties(true, Set.of("default"), many);
    assertThat(ChatGptDelegationSchema.constrain("delegateTask", REQUEST, configured))
        .isEqualTo(REQUEST);
    assertThatCode(() -> configured.check("default", "model-16")).doesNotThrowAnyException();
    assertThat(
            ChatGptDelegationSchema.constrain(
                "delegateTask",
                REQUEST,
                new PlanDelegationProperties(
                    true, Set.of("default"), Set.of("valid", "model/unsafe"))))
        .isEqualTo(REQUEST);
  }
}
