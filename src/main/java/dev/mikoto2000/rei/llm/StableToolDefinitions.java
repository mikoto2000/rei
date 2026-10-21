package dev.mikoto2000.rei.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 用途に応じたモデル利用に必要な安定したツール定義一覧を扱う。
 *
 * <p>Canonicalizes only supplied definitions. Callback authority and context are delegated
 * unchanged.
 */
public final class StableToolDefinitions {
  /** データの変換。 */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private StableToolDefinitions() {}

  /** 用途に応じたモデル利用で用いるプロンプトを返す。 */
  public static Prompt prompt(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
        || options.getToolCallbacks() == null
        || options.getToolCallbacks().isEmpty()) return prompt;
    return new Prompt(
        prompt.getInstructions(),
        options.mutate().toolCallbacks(callbacks(options.getToolCallbacks())).build());
  }

  /** 用途に応じたモデル利用で用いる完了時の処理一覧を返す。 */
  public static List<ToolCallback> callbacks(List<ToolCallback> callbacks) {
    return callbacks.stream()
        .sorted(Comparator.comparing(c -> c.getToolDefinition().name()))
        .map(StableToolDefinitions::canonical)
        .toList();
  }

  /** 用途に応じたモデル利用で用いる正規を返す。 */
  private static ToolCallback canonical(ToolCallback callback) {
    var original = callback.getToolDefinition();
    String schema = original.inputSchema();
    try {
      schema = MAPPER.writeValueAsString(ordered(MAPPER.readTree(schema)));
    } catch (Exception invalid) {
      return callback;
    }
    if (schema.equals(original.inputSchema())) return callback;
    var definition =
        ToolDefinition.builder()
            .name(original.name())
            .description(original.description())
            .inputSchema(schema)
            .build();
    return dev.mikoto2000.rei.core.contextbudget.DeliveryToolRegistration.preservePolicy(
        callback,
        new ToolCallback() {
          /** 用途に応じたモデル利用で参照するツール定義を返す。 */
          public ToolDefinition getToolDefinition() {
            return definition;
          }

          /** 用途に応じたモデル利用で参照するツール付随情報を返す。 */
          public ToolMetadata getToolMetadata() {
            return callback.getToolMetadata();
          }

          /** 用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input) {
            return callback.call(input);
          }

          /** 用途に応じたモデル利用の要求を実行し、呼び出し元へ結果を返す。 */
          public String call(String input, org.springframework.ai.chat.model.ToolContext context) {
            return callback.call(input, context);
          }
        });
  }

  /** 用途に応じたモデル利用で用いるorderedを返す。 */
  private static JsonNode ordered(JsonNode node) {
    if (node == null) return MAPPER.nullNode();
    if (node.isObject()) {
      var result = MAPPER.createObjectNode();
      var names = new TreeSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      for (var name : names) result.set(name, ordered(node.get(name)));
      return result;
    }
    if (node.isArray()) {
      var result = MAPPER.createArrayNode();
      node.forEach(value -> result.add(ordered(value)));
      return result;
    }
    return node;
  }
}
