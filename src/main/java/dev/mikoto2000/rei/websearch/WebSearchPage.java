package dev.mikoto2000.rei.websearch;

public record WebSearchPage(
    String title,
    String url,
    String snippet,
    String publishedAt,
    String content,
    boolean truncated) {

  public WebSearchPage(String title, String url, String snippet, String publishedAt, String content) {
    this(title, url, snippet, publishedAt, content, false);
  }
}
