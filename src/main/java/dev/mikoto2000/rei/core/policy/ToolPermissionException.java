package dev.mikoto2000.rei.core.policy;

public class ToolPermissionException extends SecurityException {
  private String approvalId;

  public String approvalId() {
    return approvalId;
  }

  public ToolPermissionException withApprovalId(String id) {
    approvalId = id;
    return this;
  }

  private final PermissionDecision decision;

  public ToolPermissionException(String tool, PermissionDecision decision) {
    super(
        "Tool permission: "
            + decision
            + " for "
            + tool
            + "; change administrator policy and explicitly retry the request");
    this.decision = decision;
  }

  public PermissionDecision decision() {
    return decision;
  }
}
