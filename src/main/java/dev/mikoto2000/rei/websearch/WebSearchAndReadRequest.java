package dev.mikoto2000.rei.websearch;

/** Web 検索と上位ページ本文取得の入力。 */
public record WebSearchAndReadRequest(String query, Integer maxResults, Integer readTop) {
}
