package dev.mikoto2000.rei.websearch;

public record WebSearchPage(
    String title,
    String url,
    String snippet,
    String publishedAt,
    String content,
    boolean truncated,
    @com.fasterxml.jackson.annotation.JsonIgnore String fingerprint,
    java.util.List<WebSourceAlias> aliases) {

  public WebSearchPage { aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases); }
  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content, boolean truncated) {
    this(title, url, snippet, publishedAt, content, truncated, null,
        java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }
  public WebSearchPage withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchPage(title, url, snippet, publishedAt, content, truncated, fingerprint, combined);
  }

  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content) {
    this(title, url, snippet, publishedAt, content, false);
  }
}
