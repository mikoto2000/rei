package dev.mikoto2000.rei.subagent;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 補助エージェントへの委譲で受け渡す補助エージェント出力を表す。
 *
 * <p>Validated model envelope; its task status is distinct from execution status.
 *
 * @param status 状態
 * @param summary 要約
 * @param result 結果
 * @param warnings 注意事項
 */
public record SubAgentOutput(
    Status status, String summary, JsonNode result, List<String> warnings) {
  /** 補助エージェントへの委譲における状態の選択肢。 */
  public enum Status {
    /** 成功。 */
    SUCCESS,
    /** 失敗情報。 */
    FAILURE,
    /** 部分的な結果。 */
    PARTIAL
  }

  /** 受け取った情報からSubAgentOutputを作成する。 */
  public SubAgentOutput {
    result = result.deepCopy();
    warnings = List.copyOf(warnings);
  }

  /** 補助エージェントへの委譲で用いる結果を返す。 */
  @Override
  public JsonNode result() {
    return result.deepCopy();
  }

  /** 補助エージェントへの委譲で用いる起点検証済みを返す。 */
  static SubAgentOutput fromValidated(JsonNode json) {
    return new SubAgentOutput(
        Status.valueOf(json.get("status").asString()),
        json.get("summary").asString(),
        json.get("result"),
        json.get("warnings").valueStream().map(JsonNode::asString).toList());
  }
}
