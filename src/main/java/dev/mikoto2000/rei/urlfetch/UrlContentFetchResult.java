package dev.mikoto2000.rei.urlfetch;

/**
 * URLの内容の参照で受け渡すURL内容取得結果を表す。
 *
 * @param success 成功
 * @param content 内容
 * @param errorType エラー種別
 * @param errorMessage エラーメッセージ
 * @param statusCode 応答の状態コード
 * @param contentType 内容の種類
 * @param finalUrl 最終URL
 * @param retrievedAt retrieved日時
 * @param validatedAt 検証済み日時
 * @param truncated 省略の有無
 * @param omissions 省略した内容
 * @param dataTrust データTrust
 */
public record UrlContentFetchResult(
    boolean success,
    String content,
    String errorType,
    String errorMessage,
    Integer statusCode,
    String contentType,
    String finalUrl,
    java.time.Instant retrievedAt,
    java.time.Instant validatedAt,
    boolean truncated,
    java.util.List<String> omissions,
    String dataTrust) {

  /** 受け取った情報からUrlContentFetchResultを作成する。 */
  public UrlContentFetchResult {
    omissions = omissions == null ? java.util.List.of() : java.util.List.copyOf(omissions);
  }

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchResult(
      boolean success,
      String content,
      String errorType,
      String errorMessage,
      Integer statusCode,
      String contentType,
      String finalUrl,
      java.time.Instant retrievedAt,
      java.time.Instant validatedAt) {
    this(
        success,
        content,
        errorType,
        errorMessage,
        statusCode,
        contentType,
        finalUrl,
        retrievedAt,
        validatedAt,
        false,
        java.util.List.of(),
        "external_untrusted");
  }

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchResult(
      boolean success,
      String content,
      String errorType,
      String errorMessage,
      Integer statusCode,
      String contentType,
      String finalUrl) {
    this(success, content, errorType, errorMessage, statusCode, contentType, finalUrl, null, null);
  }

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchResult(
      boolean success,
      String content,
      String errorType,
      String errorMessage,
      Integer statusCode,
      String contentType) {
    this(success, content, errorType, errorMessage, statusCode, contentType, null);
  }

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchResult(
      boolean success, String content, String errorType, String errorMessage, Integer statusCode) {
    this(success, content, errorType, errorMessage, statusCode, null);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static UrlContentFetchResult success(String content) {
    return success(content, null);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static UrlContentFetchResult success(String content, String contentType) {
    return new UrlContentFetchResult(true, content, null, null, null, contentType);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static UrlContentFetchResult success(String content, String contentType, String finalUrl) {
    return new UrlContentFetchResult(true, content, null, null, null, contentType, finalUrl);
  }

  /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
  public static UrlContentFetchResult success(
      String content,
      String contentType,
      String finalUrl,
      java.time.Instant retrievedAt,
      java.time.Instant validatedAt) {
    return new UrlContentFetchResult(
        true, content, null, null, null, contentType, finalUrl, retrievedAt, validatedAt);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  public static UrlContentFetchResult failure(String errorType, String errorMessage) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, null, null);
  }

  /** 処理を継続できなかった理由を、失敗情報として表す。 */
  public static UrlContentFetchResult failure(
      String errorType, String errorMessage, Integer statusCode) {
    return new UrlContentFetchResult(false, null, errorType, errorMessage, statusCode, null);
  }
}
