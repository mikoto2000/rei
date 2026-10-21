package dev.mikoto2000.rei.core.chat;

/**
 * 会話の実行で受け渡すチャット実行結果を表す。
 *
 * @param status 状態
 * @param text テキスト
 * @param memoryConsolidationSuggested 記憶統合Suggested
 * @param errorMessage エラーメッセージ
 * @param stopCode 停止コード
 */
public record ChatExecutionResult(
    Status status,
    String text,
    boolean memoryConsolidationSuggested,
    String errorMessage,
    String stopCode) {
  /** 受け取った情報からChatExecutionResultを作成する。 */
  public ChatExecutionResult {
    stopCode = stopCode == null ? "" : stopCode;
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatExecutionResult(Status status, String text, boolean suggested, String errorMessage) {
    this(status, text, suggested, errorMessage, "");
  }

  /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
  public static ChatExecutionResult failed(String errorMessage, String stopCode) {
    return new ChatExecutionResult(Status.FAILED, "", false, errorMessage, stopCode);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static ChatExecutionResult success(String text, boolean memoryConsolidationSuggested) {
    return new ChatExecutionResult(
        Status.SUCCESS, text == null ? "" : text, memoryConsolidationSuggested, null);
  }

  /** 対象の処理が失敗したことを、理由を含む結果として表す。 */
  public static ChatExecutionResult failed(String errorMessage) {
    return new ChatExecutionResult(Status.FAILED, "", false, errorMessage);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public boolean success() {
    return status == Status.SUCCESS;
  }

  /** 会話の実行で用いるキャンセル済みを返す。 */
  public static ChatExecutionResult cancelled() {
    return new ChatExecutionResult(Status.CANCELLED, "", false, "chat run cancelled");
  }

  /** 会話の実行で用いる待機中を返す。 */
  public static ChatExecutionResult waiting() {
    return waiting("external_dependency_waiting");
  }

  /** 会話の実行で用いる待機中を返す。 */
  public static ChatExecutionResult waiting(String reason) {
    return new ChatExecutionResult(Status.WAITING, "", false, null, reason);
  }

  /** 会話の実行における状態の選択肢。 */
  public enum Status {
    /** 成功。 */
    SUCCESS,
    /** 失敗。 */
    FAILED,
    /** キャンセル済み。 */
    CANCELLED,
    /** 待機中。 */
    WAITING
  }
}
