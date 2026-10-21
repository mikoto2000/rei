package dev.mikoto2000.rei.chatgpt;

import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ChatGPTとの連携に必要なチャットGPT委譲データ形式を扱う。
 *
 * <p>Advertise current ChatGPT choices without narrowing local or Codex backend arguments.
 */
public final class ChatGptDelegationSchema {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ChatGptDelegationSchema() {}

  /** ChatGPTとの連携で用いるconstrainを返す。 */
  public static String constrain(
      String toolName, String inputSchema, PlanDelegationProperties automatic) {
    if (!List.of("delegateTask", "delegateTasks").contains(toolName)) return inputSchema;
    var models = ChatGptModelChoices.models(automatic);
    if (models.isEmpty()) return inputSchema;
    try {
      var mapper = JsonMapper.builder().build();
      var schema = mapper.readTree(inputSchema);
      var request =
          "delegateTasks".equals(toolName)
              ? schema.path("properties").path("requests").path("items")
              : schema;
      if (!(request instanceof ObjectNode node)) return inputSchema;
      var existing = node.get("allOf");
      if (existing != null && !existing.isArray()) return inputSchema;
      var rule = mapper.createObjectNode();
      var condition = rule.putObject("if");
      condition.putArray("required").add("backendId");
      condition.putObject("properties").putObject("backendId").put("const", "chatgpt-plan");
      var then = rule.putObject("then");
      then.putArray("required").add("modelId");
      var choices = then.putObject("properties").putObject("modelId").putArray("enum");
      models.forEach(choices::add);
      if ("delegateTasks".equals(toolName) && node.has("$ref")) {
        // Wrap references rather than relying on $ref sibling semantics of a particular dialect.
        var constrained = mapper.createObjectNode();
        constrained.putArray("allOf").add(node).add(rule);
        ((ObjectNode) schema.path("properties").path("requests")).set("items", constrained);
      } else if (existing == null) node.putArray("allOf").add(rule);
      else ((tools.jackson.databind.node.ArrayNode) existing).add(rule);
      return mapper.writeValueAsString(schema);
    } catch (RuntimeException malformed) {
      return inputSchema;
    }
  }
}
