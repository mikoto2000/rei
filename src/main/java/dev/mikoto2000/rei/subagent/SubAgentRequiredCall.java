package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 補助エージェントへの委譲で受け渡す補助エージェント必須呼び出しを表す。
 *
 * <p>Exact arguments and optional top-level output field contract; object key order is immaterial.
 *
 * @param tool ツール
 * @param argumentsJson 引数JSON変換
 * @param expectedOutputJson 期待する内容出力JSON変換
 */
public record SubAgentRequiredCall(String tool, String argumentsJson, String expectedOutputJson) {
  /** データの変換。 */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 入力の解釈。 */
  private static final SubAgentResultParser PARSER = new SubAgentResultParser();

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentRequiredCall(String tool, String argumentsJson) {
    this(tool, argumentsJson, null);
  }

  /** 受け取った情報からSubAgentRequiredCallを作成する。 */
  public SubAgentRequiredCall {
    if (tool == null
        || !tool.matches("[A-Za-z][A-Za-z0-9_]{0,99}")
        || argumentsJson == null
        || argumentsJson.length() > 4096)
      throw new IllegalArgumentException("requiredToolCalls: invalid tool or oversized arguments");
    try {
      var value = PARSER.parse(argumentsJson);
      if (value == null || !value.isObject()) throw new IllegalArgumentException();
      argumentsJson = MAPPER.writeValueAsString(value);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("requiredToolCalls: arguments must be a JSON object");
    }
    if (expectedOutputJson != null) {
      try {
        if (expectedOutputJson.length() > 4096) throw new IllegalArgumentException();
        var expected = PARSER.parse(expectedOutputJson);
        if (!expected.isObject() || expected.isEmpty()) throw new IllegalArgumentException();
        expectedOutputJson = MAPPER.writeValueAsString(expected);
      } catch (RuntimeException error) {
        throw new IllegalArgumentException(
            "requiredToolCalls: expectedOutput must be a nonempty bounded JSON object");
      }
    }
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  boolean matches(String observedTool, JsonNode arguments) {
    return tool.equals(observedTool)
        && arguments != null
        && MAPPER.readTree(argumentsJson).equals(arguments);
  }

  /** 条件に合う対象を選ぶため、出力との一致を判定する。 */
  boolean matchesOutput(String output, boolean truncated) {
    if (expectedOutputJson == null) return true;
    if (truncated) return false;
    try {
      var actual = PARSER.parse(output);
      var expected = MAPPER.readTree(expectedOutputJson);
      return actual.isObject()
          && expected.properties().stream()
              .allMatch(field -> field.getValue().equals(actual.get(field.getKey())));
    } catch (RuntimeException error) {
      return false;
    }
  }
}
