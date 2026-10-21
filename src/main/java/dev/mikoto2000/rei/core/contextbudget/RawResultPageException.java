package dev.mikoto2000.rei.core.contextbudget;

/**
 * モデルへ渡す文脈の管理を継続できない元の結果ページの状況を伝える例外。
 *
 * <p>Fixed diagnostics for trusted raw-result page envelopes; never retains the supplied page.
 */
public final class RawResultPageException extends IllegalArgumentException {
  /** モデルへ渡す文脈の管理における理由の選択肢。 */
  public enum Reason {
    /** 不正情報の来歴。 */
    INVALID_PROVENANCE,
    /** 取得元利用不可。 */
    SOURCE_UNAVAILABLE
  }

  /** 理由。 */
  private final Reason reason;

  /** 指定された値や利用先を使って、RawResultPageExceptionを初期化する。 */
  public RawResultPageException(Reason reason) {
    super(reason.name());
    this.reason = reason;
  }

  /** モデルへ渡す文脈の管理で用いる理由を返す。 */
  public Reason reason() {
    return reason;
  }

  /** モデルへ渡す文脈の管理で用いる診断を返す。 */
  public dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic diagnostic() {
    return reason == Reason.INVALID_PROVENANCE
        ? new dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic(
            "raw_page_invalid_provenance",
            "保存済みツール結果のページ形式または参照情報が不正です。最新の Rei で再開し、readRawToolResult を再取得してください。")
        : new dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics.Diagnostic(
            "raw_page_source_unavailable",
            "ページの元のツール結果をこの会話から参照できません。保存先の存在・権限と会話を確認してください。元結果を確認するまで副作用のあるツールを再実行しないでください。");
  }
}
