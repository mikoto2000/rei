package dev.mikoto2000.rei.websearch;

public record WebSearchResult(
    String title,
    String url,
    String snippet,
    String publishedAt) {
}
