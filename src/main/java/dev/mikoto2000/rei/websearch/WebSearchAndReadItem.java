package dev.mikoto2000.rei.websearch;

/**
 * 検索順位を保った検索結果と本文取得状態。
 *
 * @param title タイトル
 * @param url 参照先のURL
 * @param snippet 抜粋
 * @param publishedAt 公開日時
 * @param content 内容
 * @param contentType 内容の種類
 * @param fetchStatus 取得状態
 * @param errorType エラー種別
 * @param errorMessage エラーメッセージ
 * @param truncated 省略の有無
 * @param fingerprint 要求内容の照合値
 * @param aliases 別名一覧
 * @param retrievedAt retrieved日時
 * @param validatedAt 検証済み日時
 * @param excerpts 根拠として示す抜粋
 * @param omissions 省略した内容
 * @param dataTrust データTrust
 */
public record WebSearchAndReadItem(
    String title,
    String url,
    String snippet,
    String publishedAt,
    String content,
    String contentType,
    String fetchStatus,
    String errorType,
    String errorMessage,
    boolean truncated,
    @com.fasterxml.jackson.annotation.JsonIgnore String fingerprint,
    java.util.List<WebSourceAlias> aliases,
    java.time.Instant retrievedAt,
    java.time.Instant validatedAt,
    java.util.List<WebExcerpt> excerpts,
    java.util.List<String> omissions,
    String dataTrust) {
  /** 受け取った情報からWebSearchAndReadItemを作成する。 */
  public WebSearchAndReadItem {
    aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
    excerpts = excerpts == null ? java.util.List.of() : java.util.List.copyOf(excerpts);
    omissions = omissions == null ? java.util.List.of() : java.util.List.copyOf(omissions);
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadItem(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      String contentType,
      String fetchStatus,
      String errorType,
      String errorMessage,
      boolean truncated,
      String fingerprint,
      java.util.List<WebSourceAlias> aliases) {
    this(
        title,
        url,
        snippet,
        publishedAt,
        content,
        contentType,
        fetchStatus,
        errorType,
        errorMessage,
        truncated,
        fingerprint,
        aliases,
        null,
        null,
        java.util.List.of(),
        java.util.List.of(),
        "external_untrusted");
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchAndReadItem(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      String contentType,
      String fetchStatus,
      String errorType,
      String errorMessage,
      boolean truncated) {
    this(
        title,
        url,
        snippet,
        publishedAt,
        content,
        contentType,
        fetchStatus,
        errorType,
        errorMessage,
        truncated,
        null,
        java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }

  /** 指定された別名一覧を反映した情報を作成する。 */
  public WebSearchAndReadItem withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchAndReadItem(
        title,
        url,
        snippet,
        publishedAt,
        content,
        contentType,
        fetchStatus,
        errorType,
        errorMessage,
        truncated,
        fingerprint,
        combined,
        retrievedAt,
        validatedAt,
        excerpts,
        omissions,
        dataTrust);
  }

  /** 後続の処理が参照できるよう、ページとして扱う。 */
  WebSearchPage asPage() {
    return new WebSearchPage(
        title,
        url,
        snippet,
        publishedAt,
        content,
        truncated,
        fingerprint,
        aliases,
        fetchStatus,
        errorType,
        retrievedAt,
        validatedAt,
        excerpts,
        omissions,
        dataTrust);
  }

  /** Web上の情報の調査で用いる起点ページを返す。 */
  static WebSearchAndReadItem fromPage(
      WebSearchPage page, String contentType, String errorMessage) {
    return new WebSearchAndReadItem(
        page.title(),
        page.url(),
        page.snippet(),
        page.publishedAt(),
        page.content(),
        contentType,
        page.fetchStatus(),
        page.errorType(),
        errorMessage,
        page.truncated(),
        page.fingerprint(),
        page.aliases(),
        page.retrievedAt(),
        page.validatedAt(),
        page.excerpts(),
        page.omissions(),
        page.dataTrust());
  }
}
