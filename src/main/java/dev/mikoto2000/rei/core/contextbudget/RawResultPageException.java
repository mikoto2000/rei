package dev.mikoto2000.rei.core.contextbudget;

/** Fixed diagnostics for trusted raw-result page envelopes; never retains the supplied page. */
public final class RawResultPageException extends IllegalArgumentException {
  public enum Reason { INVALID_PROVENANCE, SOURCE_UNAVAILABLE }
  private final Reason reason;
  public RawResultPageException(Reason reason) {super(reason.name());this.reason=reason;}
  public Reason reason(){return reason;}
  public dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic diagnostic() {
    return reason==Reason.INVALID_PROVENANCE
        ?new dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic("raw_page_invalid_provenance",
            "保存済みツール結果のページ形式または参照情報が不正です。最新の Rei で再開し、readRawToolResult を再取得してください。")
        :new dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic("raw_page_source_unavailable",
            "ページの元のツール結果をこの会話から参照できません。保存先の存在・権限と会話を確認してください。元結果を確認するまで副作用のあるツールを再実行しないでください。");
  }
}
