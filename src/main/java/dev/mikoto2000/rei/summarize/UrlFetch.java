package dev.mikoto2000.rei.summarize;

/**
 * 情報の要約で受け渡すURL取得を表す。
 *
 * @param success 成功
 * @param content 内容
 * @param errorType エラー種別
 * @param errorMessage エラーメッセージ
 * @param statusCode 応答の状態コード
 */
public record UrlFetch(
    boolean success, String content, String errorType, String errorMessage, Integer statusCode) {

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static UrlFetch success(String content) {
    return new UrlFetch(true, content, null, null, null);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  public static UrlFetch failure(String errorType, String errorMessage) {
    return new UrlFetch(false, null, errorType, errorMessage, null);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  public static UrlFetch failure(String errorType, String errorMessage, Integer statusCode) {
    return new UrlFetch(false, null, errorType, errorMessage, statusCode);
  }
}
