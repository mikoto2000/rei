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
    java.util.List<WebSourceAlias> aliases) {
  public WebSearchAndReadItem { aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases); }
  public WebSearchAndReadItem(String title, String url, String snippet, String publishedAt, String content,
      String contentType, String fetchStatus, String errorType, String errorMessage, boolean truncated) {
    this(title, url, snippet, publishedAt, content, contentType, fetchStatus, errorType, errorMessage, truncated,
        null, java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }
  public WebSearchAndReadItem withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchAndReadItem(title, url, snippet, publishedAt, content, contentType, fetchStatus,
        errorType, errorMessage, truncated, fingerprint, combined);
  }
}
