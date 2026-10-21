package dev.mikoto2000.rei.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** MCPIngress判断基準の結果と状態の契約を検証するテスト。 */
class McpIngressPolicyTest {
  /** MCPによる機能の提供で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void acceptsOnlyDedicatedLoopbackConnectorAndExactHost() {
    var policy = new McpIngressPolicy(8091);
    assertTrue(policy.allows("127.0.0.1", "127.0.0.1", 8091, "127.0.0.1:8091", null));
    assertFalse(policy.allows("192.168.1.2", "127.0.0.1", 8091, "127.0.0.1:8091", null));
    assertFalse(policy.allows("127.0.0.1", "192.168.1.2", 8080, "127.0.0.1:8091", null));
    assertFalse(policy.allows("127.0.0.1", "127.0.0.1", 8091, "evil.test:8091", null));
    assertFalse(
        policy.allows("127.0.0.1", "127.0.0.1", 8091, "127.0.0.1:8091", "https://evil.test"));
    assertFalse(policy.allows("127.0.0.1", "127.0.0.1", 8091, "127.0.0.1:8091", "null"));
    assertTrue(
        policy.allows("127.0.0.1", "127.0.0.1", 8091, "127.0.0.1:8091", "http://127.0.0.1:8091"));
  }
}
