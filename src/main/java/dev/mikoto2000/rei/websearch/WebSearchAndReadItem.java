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
    boolean truncated) {
}
