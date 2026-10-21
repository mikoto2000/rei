package dev.mikoto2000.rei.mcp;

/** MCPによる機能の提供に必要なMCPエラーを扱う。 */
public final class McpError extends RuntimeException {
  /** コード。 */
  private final String code;

  /** 指定された値や利用先を使って、McpErrorを初期化する。 */
  public McpError(String code) {
    super(code);
    this.code = code;
  }

  /** MCPによる機能の提供で用いるコードを返す。 */
  public String code() {
    return code;
  }
}
