package dev.mikoto2000.rei.websearch;

/** Citation metadata remains untrusted source data. */
public record WebSourceAlias(String url, String title, String publishedAt, String evidenceType) {
  public WebSourceAlias(String url, String title, String publishedAt) { this(url, title, publishedAt, "search_metadata"); }
  public static WebSourceAlias from(WebSearchResult result) { return new WebSourceAlias(result.url(), result.title(), result.publishedAt()); }
}
