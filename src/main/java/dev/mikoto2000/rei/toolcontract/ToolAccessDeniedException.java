package dev.mikoto2000.rei.toolcontract;

/** アクセスを許可せず、安全な理由と復旧手順だけをモデルへ返す失敗。 */
public final class ToolAccessDeniedException extends IllegalArgumentException {
  private final String code;
  private final String diagnosticMessage;
  private final String guidance;

  /** サーバー定義の固定コードと案内だけを受け取り、保護対象の情報は保持しない。 */
  public ToolAccessDeniedException(String code, String diagnosticMessage, String guidance) {
    super(code);
    this.code = code;
    this.diagnosticMessage = diagnosticMessage;
    this.guidance = guidance;
  }

  /** 呼び出し元とモデルへ渡す拒否コードを返す。 */
  public String code() {
    return code;
  }

  /** イベント表示に使う安全な拒否理由を返す。 */
  public String diagnosticMessage() {
    return diagnosticMessage;
  }

  /** 後続の判断に必要な復旧手順を含めた失敗結果を返す。 */
  public String result() {
    return new tools.jackson.databind.json.JsonMapper()
        .writeValueAsString(
            java.util.Map.of(
                "success",
                false,
                "error",
                code,
                "message",
                diagnosticMessage,
                "guidance",
                guidance));
  }
}
