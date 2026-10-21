package dev.mikoto2000.rei.mcp;

import java.time.Duration;

/**
 * MCPによる機能の提供で受け渡すMCP受付を表す。
 *
 * @param requestHash 要求ハッシュ値
 * @param fingerprint 要求内容の照合値
 * @param retention 保持期間
 * @param totalCriteria 合計判断基準一覧
 * @param criteria 判断基準一覧
 */
public record McpAdmission(
    String requestHash,
    String fingerprint,
    Duration retention,
    int totalCriteria,
    java.util.List<String> criteria) {
  /** 受け取った情報からMcpAdmissionを作成する。 */
  public McpAdmission {
    criteria = java.util.List.copyOf(criteria);
    if (requestHash == null
        || !requestHash.matches("[a-f0-9]{64}")
        || fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || retention == null
        || retention.isNegative()
        || retention.isZero()) throw new IllegalArgumentException("Invalid MCP admission");
  }
}
