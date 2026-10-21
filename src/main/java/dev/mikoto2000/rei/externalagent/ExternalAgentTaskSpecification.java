package dev.mikoto2000.rei.externalagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 外部エージェントへの委譲で受け渡す外部エージェントタスク仕様を表す。
 *
 * <p>Versioned task resources, independent of command grammar and provider startup.
 *
 * @param promptResource プロンプト補助資料
 * @param schemaResource データ形式補助資料
 */
record ExternalAgentTaskSpecification(String promptResource, String schemaResource) {
  /** 外部エージェントへの委譲で用いる対象操作を返す。 */
  static ExternalAgentTaskSpecification forAction(ExternalAgentRequest.Action action) {
    return switch (action) {
      case REVIEW ->
          new ExternalAgentTaskSpecification(
              "/external-agent/review.txt", "/external-agent/schema.json");
      case MATERIAL_REVIEW ->
          new ExternalAgentTaskSpecification(
              "/external-agent/material-review.md",
              "/subagents/schemas/material-review.schema.json");
      case PROPOSE_FIX ->
          new ExternalAgentTaskSpecification(
              "/external-agent/fix-proposal.txt", "/external-agent/fix-proposal-schema.json");
      case IMPLEMENT ->
          new ExternalAgentTaskSpecification(
              "/external-agent/implementation.txt", "/external-agent/implementation-schema.json");
    };
  }

  /** 外部エージェントへの委譲で用いるプロンプトを返す。 */
  String prompt() throws IOException {
    return read(promptResource);
  }

  /** 外部エージェントへの委譲で用いるデータ形式を返す。 */
  String schema() throws IOException {
    return read(schemaResource);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private String read(String resource) throws IOException {
    try (var stream = ExternalAgentTaskSpecification.class.getResourceAsStream(resource)) {
      if (stream == null) throw new IOException("Missing external task resource");
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
