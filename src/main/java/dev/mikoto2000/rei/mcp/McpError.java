package dev.mikoto2000.rei.mcp;
public final class McpError extends RuntimeException {
  private final String code;
  public McpError(String code){super(code);this.code=code;}
  public String code(){return code;}
}
