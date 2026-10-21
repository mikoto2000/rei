package dev.mikoto2000.rei.subagent;

import tools.jackson.databind.JsonNode;

/** 補助エージェントへの委譲に必要な期待する内容出力条件一覧を扱う。 */
public final class ExpectedOutputConditions {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ExpectedOutputConditions() {}

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public static JsonNode validate(String raw, SubAgentResultSchema schema) {
    String help =
        "expectedOutput must be a nonempty JSON object of literal, exact-match result-field conditions, not JSON Schema or type placeholders. Put format requirements in task/constraints. For reviewer, {\"unverified\":[]} requires exactly no unverified items; it is not a generic output template.";
    JsonNode conditions;
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (raw == null || raw.length() > 16384) throw new IllegalArgumentException();
      conditions = new SubAgentResultParser().parse(raw);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException(help);
    }
    if (!conditions.isObject() || conditions.isEmpty()) throw new IllegalArgumentException(help);
    if (schema != null && !schema.validateConditions(conditions).isEmpty())
      throw new IllegalArgumentException(
          help
              + " Specified fields or values contradict the selected role resultSchema (check field names, types and enum values).");
    return conditions;
  }
}
