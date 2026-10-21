package dev.mikoto2000.rei.websearch;

/**
 * Web上の情報の調査で受け渡すWeb検索ページを表す。
 *
 * @param title タイトル
 * @param url 参照先のURL
 * @param snippet 抜粋
 * @param publishedAt 公開日時
 * @param content 内容
 * @param truncated 省略の有無
 * @param fingerprint 要求内容の照合値
 * @param aliases 別名一覧
 * @param fetchStatus 取得状態
 * @param errorType エラー種別
 * @param retrievedAt retrieved日時
 * @param validatedAt 検証済み日時
 * @param excerpts 根拠として示す抜粋
 * @param omissions 省略した内容
 * @param dataTrust データTrust
 */
public record WebSearchPage(
    String title,
    String url,
    String snippet,
    String publishedAt,
    String content,
    boolean truncated,
    @com.fasterxml.jackson.annotation.JsonIgnore String fingerprint,
    java.util.List<WebSourceAlias> aliases,
    String fetchStatus,
    String errorType,
    java.time.Instant retrievedAt,
    java.time.Instant validatedAt,
    java.util.List<WebExcerpt> excerpts,
    java.util.List<String> omissions,
    String dataTrust) {

  /** 受け取った情報からWebSearchPageを作成する。 */
  public WebSearchPage {
    aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
    excerpts = excerpts == null ? java.util.List.of() : java.util.List.copyOf(excerpts);
    omissions = omissions == null ? java.util.List.of() : java.util.List.copyOf(omissions);
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchPage(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      boolean truncated,
      String fingerprint,
      java.util.List<WebSourceAlias> aliases,
      String fetchStatus,
      String errorType) {
    this(
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
        null,
        null,
        java.util.List.of(),
        java.util.List.of(),
        "external_untrusted");
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchPage(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      boolean truncated,
      String fingerprint,
      java.util.List<WebSourceAlias> aliases) {
    this(
        title,
        url,
        snippet,
        publishedAt,
        content,
        truncated,
        fingerprint,
        aliases,
        "success",
        null);
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchPage(
      String title,
      String url,
      String snippet,
      String publishedAt,
      String content,
      boolean truncated) {
    this(
        title,
        url,
        snippet,
        publishedAt,
        content,
        truncated,
        null,
        java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }

  /** 指定された別名一覧を反映した情報を作成する。 */
  public WebSearchPage withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchPage(
        title,
        url,
        snippet,
        publishedAt,
        content,
        truncated,
        fingerprint,
        combined,
        fetchStatus,
        errorType,
        retrievedAt,
        validatedAt,
        excerpts,
        omissions,
        dataTrust);
  }

  /** 指定された根拠を反映した情報を作成する。 */
  public WebSearchPage withEvidence(
      java.time.Instant retrieved,
      java.time.Instant validated,
      java.util.List<WebExcerpt> sections,
      java.util.List<String> omitted) {
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
        retrieved,
        validated,
        sections,
        omitted,
        dataTrust);
  }

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchPage(
      String title, String url, String snippet, String publishedAt, String content) {
    this(title, url, snippet, publishedAt, content, false);
  }
}
