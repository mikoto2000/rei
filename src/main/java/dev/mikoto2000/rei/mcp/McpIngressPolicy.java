package dev.mikoto2000.rei.mcp;

/** Uses connector/socket facts only; forwarded headers never establish trust. */
public record McpIngressPolicy(int port) {
  public McpIngressPolicy {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid MCP port");
  }
  public boolean allows(String remoteAddress, String localAddress, int localPort, String host, String origin) {
    String authority = "127.0.0.1:" + port;
    return "127.0.0.1".equals(remoteAddress) && "127.0.0.1".equals(localAddress)
        && port == localPort && authority.equals(host)
        && (origin == null || ("http://" + authority).equals(origin));
  }
}
