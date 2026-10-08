package dev.mikoto2000.rei.websearch;

/** 検索順位を保った検索結果と本文取得状態。 */
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
    java.time.Instant retrievedAt, java.time.Instant validatedAt,
    java.util.List<WebExcerpt> excerpts, java.util.List<String> omissions, String dataTrust) {
  public WebSearchAndReadItem {
    aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
    excerpts = excerpts == null ? java.util.List.of() : java.util.List.copyOf(excerpts);
    omissions = omissions == null ? java.util.List.of() : java.util.List.copyOf(omissions);
  }
  public WebSearchAndReadItem(String title, String url, String snippet, String publishedAt, String content,
      String contentType, String fetchStatus, String errorType, String errorMessage, boolean truncated,
      String fingerprint, java.util.List<WebSourceAlias> aliases) {
    this(title,url,snippet,publishedAt,content,contentType,fetchStatus,errorType,errorMessage,truncated,fingerprint,aliases,
        null,null,java.util.List.of(),java.util.List.of(),"external_untrusted");
  }
  public WebSearchAndReadItem(String title, String url, String snippet, String publishedAt, String content,
      String contentType, String fetchStatus, String errorType, String errorMessage, boolean truncated) {
    this(title, url, snippet, publishedAt, content, contentType, fetchStatus, errorType, errorMessage, truncated,
        null, java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }
  public WebSearchAndReadItem withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchAndReadItem(title, url, snippet, publishedAt, content, contentType, fetchStatus,
        errorType, errorMessage, truncated, fingerprint, combined, retrievedAt, validatedAt, excerpts, omissions, dataTrust);
  }
  WebSearchPage asPage() {
    return new WebSearchPage(title,url,snippet,publishedAt,content,truncated,fingerprint,aliases,fetchStatus,errorType,
        retrievedAt,validatedAt,excerpts,omissions,dataTrust);
  }
  static WebSearchAndReadItem fromPage(WebSearchPage page, String contentType, String errorMessage) {
    return new WebSearchAndReadItem(page.title(),page.url(),page.snippet(),page.publishedAt(),page.content(),contentType,
        page.fetchStatus(),page.errorType(),errorMessage,page.truncated(),page.fingerprint(),page.aliases(),page.retrievedAt(),
        page.validatedAt(),page.excerpts(),page.omissions(),page.dataTrust());
  }
}
