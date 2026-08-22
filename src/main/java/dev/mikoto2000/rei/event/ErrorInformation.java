package dev.mikoto2000.rei.event;

/**
 * エラー情報。
 *
 * <p>Throwable 自体を保持せず、イベントとして必要な情報だけを抽出する。
 * スタックトレース全文は通常の logging に任せる。</p>
 *
 * @param errorType エラーの種別（例外クラス名など）
 * @param message エラーメッセージ
 * @param code エラーコード（任意）
 */
public record ErrorInformation(String errorType, String message, String code) {

  /** Throwable から必要な情報だけを抽出する。 */
  public static ErrorInformation from(Throwable throwable) {
    if (throwable == null) {
      return new ErrorInformation("unknown", "unknown error", null);
    }
    return new ErrorInformation(
        throwable.getClass().getSimpleName(),
        throwable.getMessage() == null ? "" : throwable.getMessage(),
        null);
  }
}
