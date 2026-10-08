package dev.mikoto2000.rei.websearch;

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
    String errorType, java.time.Instant retrievedAt, java.time.Instant validatedAt,
    java.util.List<WebExcerpt> excerpts, java.util.List<String> omissions, String dataTrust) {

  public WebSearchPage {
    aliases = aliases == null ? java.util.List.of() : java.util.List.copyOf(aliases);
    excerpts = excerpts == null ? java.util.List.of() : java.util.List.copyOf(excerpts);
    omissions = omissions == null ? java.util.List.of() : java.util.List.copyOf(omissions);
  }
  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content,
      boolean truncated, String fingerprint, java.util.List<WebSourceAlias> aliases, String fetchStatus, String errorType) {
    this(title, url, snippet, publishedAt, content, truncated, fingerprint, aliases, fetchStatus, errorType, null, null,
        java.util.List.of(), java.util.List.of(), "external_untrusted");
  }
  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content,
      boolean truncated, String fingerprint, java.util.List<WebSourceAlias> aliases) {
    this(title, url, snippet, publishedAt, content, truncated, fingerprint, aliases, "success", null);
  }
  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content, boolean truncated) {
    this(title, url, snippet, publishedAt, content, truncated, null,
        java.util.List.of(new WebSourceAlias(url, title, publishedAt)));
  }
  public WebSearchPage withAliases(java.util.List<WebSourceAlias> additional) {
    var combined = new java.util.ArrayList<>(aliases);
    for (var alias : additional) if (!combined.contains(alias)) combined.add(alias);
    return new WebSearchPage(title, url, snippet, publishedAt, content, truncated, fingerprint, combined, fetchStatus, errorType,
        retrievedAt, validatedAt, excerpts, omissions, dataTrust);
  }
  public WebSearchPage withEvidence(java.time.Instant retrieved, java.time.Instant validated,
      java.util.List<WebExcerpt> sections, java.util.List<String> omitted) {
    return new WebSearchPage(title, url, snippet, publishedAt, content, truncated, fingerprint, aliases, fetchStatus, errorType,
        retrieved, validated, sections, omitted, dataTrust);
  }

  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content) {
    this(title, url, snippet, publishedAt, content, false);
  }
}
