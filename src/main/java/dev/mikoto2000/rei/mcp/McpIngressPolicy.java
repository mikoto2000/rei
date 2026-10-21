package dev.mikoto2000.rei.mcp;

/**
 * MCPによる機能の提供で受け渡すMCPIngress判断基準を表す。
 *
 * <p>Uses connector/socket facts only; forwarded headers never establish trust.
 *
 * @param port ポート
 */
public record McpIngressPolicy(int port) {
  /** 受け取った情報からMcpIngressPolicyを作成する。 */
  public McpIngressPolicy {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid MCP port");
  }

  /** MCPによる機能の提供において、allowsに該当するか判定する。 */
  public boolean allows(
      String remoteAddress, String localAddress, int localPort, String host, String origin) {
    String authority = "127.0.0.1:" + port;
    return "127.0.0.1".equals(remoteAddress)
        && "127.0.0.1".equals(localAddress)
        && port == localPort
        && authority.equals(host)
        && (origin == null || ("http://" + authority).equals(origin));
  }
}
