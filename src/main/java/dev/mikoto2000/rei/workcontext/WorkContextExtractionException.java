package dev.mikoto2000.rei.workcontext;

import dev.mikoto2000.rei.core.diagnostic.FailureDiagnostics;

/** Controlled extraction diagnostics contain no supplied evidence or model output. */
public final class WorkContextExtractionException extends IllegalArgumentException {
  public enum Reason {
    INPUT_TOO_LARGE,
    OUTPUT_TRUNCATED,
    OUTPUT_TOO_LARGE,
    EMPTY_OUTPUT,
    INVALID_JSON,
    INVALID_SCHEMA
  }

  private final Reason reason;
  private final int measured;
  private final int limit;

  public WorkContextExtractionException(Reason reason) {
    this(reason, 0, 0, null);
  }

  public WorkContextExtractionException(Reason reason, Throwable cause) {
    this(reason, 0, 0, cause);
  }

  public WorkContextExtractionException(Reason reason, int measured, int limit) {
    this(reason, measured, limit, null);
  }

  private WorkContextExtractionException(Reason reason, int measured, int limit, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
    this.measured = Math.max(0, measured);
    this.limit = Math.max(0, limit);
  }

  public Reason reason() {
    return reason;
  }

  public FailureDiagnostics.Diagnostic diagnostic() {
    String code = "work_context_" + reason.name().toLowerCase(java.util.Locale.ROOT);
    String guidance =
        switch (reason) {
          case INPUT_TOO_LARGE ->
              "Work Context の推定入力 "
                  + measured
                  + " tokens が上限 "
                  + limit
                  + " tokens を超えました。rei.work-context.max-turns を減らすか、rei.work-context.max-input-tokens を確認してください。";
          case OUTPUT_TRUNCATED ->
              "Work Context の LLM 応答が出力上限で打ち切られました。rei.work-context.max-turns を減らすか、rei.llm.features.memory.max-output-tokens を確認してください。";
          case OUTPUT_TOO_LARGE ->
              "Work Context の応答 " + measured + " 文字が上限 " + limit + " 文字を超えました。対象の履歴を減らしてください。";
          case EMPTY_OUTPUT -> "Work Context の LLM 応答が空でした。Memory モデルの設定と稼働状況を確認してください。";
          case INVALID_JSON ->
              "Work Context の LLM 応答が JSON として読み取れませんでした。Memory モデルの構造化出力対応を確認してください。";
          case INVALID_SCHEMA ->
              "Work Context の LLM 応答が指定スキーマと一致しませんでした。Memory モデルの構造化出力対応を確認してください。";
        };
    return new FailureDiagnostics.Diagnostic(
        code, guidance + " 保存済みの Work Context は変更していません。/work update で再実行できます。");
  }
}
