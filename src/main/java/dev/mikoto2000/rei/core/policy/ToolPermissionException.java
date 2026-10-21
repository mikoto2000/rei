package dev.mikoto2000.rei.core.policy;

/** ツール利用の権限制御を継続できないツール権限の状況を伝える例外。 */
public class ToolPermissionException extends SecurityException {
  /** 承認識別子。 */
  private String approvalId;

  /** ツール利用の権限制御で用いる承認識別子を返す。 */
  public String approvalId() {
    return approvalId;
  }

  /** 指定された承認識別子を反映した情報を作成する。 */
  public ToolPermissionException withApprovalId(String id) {
    approvalId = id;
    return this;
  }

  /** 判断結果。 */
  private final PermissionDecision decision;

  /** 指定された値や利用先を使って、ToolPermissionExceptionを初期化する。 */
  public ToolPermissionException(String tool, PermissionDecision decision) {
    super(
        "Tool permission: "
            + decision
            + " for "
            + tool
            + "; change administrator policy and explicitly retry the request");
    this.decision = decision;
  }

  /** ツール利用の権限制御で用いる判断結果を返す。 */
  public PermissionDecision decision() {
    return decision;
  }
}
