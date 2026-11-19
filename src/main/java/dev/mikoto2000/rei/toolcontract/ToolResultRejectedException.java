package dev.mikoto2000.rei.toolcontract;

import java.util.List;

/** A typed validation rejection with no accepted result or unknown side effect. */
public class ToolResultRejectedException extends IllegalArgumentException {
  @java.io.Serial private static final long serialVersionUID = 7975441331554777654L;
  private final String code;
  private final List<ArgumentIssue> issues;

  public ToolResultRejectedException(String code, String guidance) {
    this(code, guidance, List.of(new ArgumentIssue("arguments", code + ": " + guidance)));
  }

  public ToolResultRejectedException(String code, String guidance, List<ArgumentIssue> issues) {
    super(code + ": " + guidance);
    this.code = code;
    this.issues = List.copyOf(issues);
  }

  public String code() {
    return code;
  }

  public List<ArgumentIssue> issues() {
    return issues;
  }

  public static ToolResultRejectedException find(Throwable failure) {
    for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause())
      if (failure instanceof ToolResultRejectedException rejected) return rejected;
    return null;
  }
}
