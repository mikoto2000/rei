package dev.mikoto2000.rei.toolcontract;

import java.util.List;

/** A read validated its arguments but returned no source content. */
public final class ToolReadRejectedException extends ToolResultRejectedException {
  @java.io.Serial private static final long serialVersionUID = 1276288703723944066L;

  private final String code;

  public ToolReadRejectedException(String code, String guidance) {
    this(code, guidance, new ArgumentIssue("arguments", guidance));
  }

  public ToolReadRejectedException(String code, String guidance, ArgumentIssue... issues) {
    super(code + ": " + guidance, List.of(issues));
    this.code = code;
  }

  public String code() {
    return code;
  }
}
