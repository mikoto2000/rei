package dev.mikoto2000.rei.toolcontract;

/** A read validated its arguments but returned no source content. */
public final class ToolReadRejectedException extends ToolResultRejectedException {
  @java.io.Serial private static final long serialVersionUID = 1276288703723944066L;

  private final String code;

  public ToolReadRejectedException(String code, String guidance) {
    super(code + ": " + guidance);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
