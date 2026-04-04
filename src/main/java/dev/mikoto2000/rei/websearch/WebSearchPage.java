package dev.mikoto2000.rei.websearch;

public record WebSearchPage(
    String title,
    String url,
    String snippet,
    String publishedAt,
    String content) {
}
