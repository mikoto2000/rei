package dev.mikoto2000.rei.websearch;

/**
 * Web上の情報の調査で受け渡すWeb検索結果を表す。
 *
 * @param title タイトル
 * @param url 参照先のURL
 * @param snippet 抜粋
 * @param publishedAt 公開日時
 */
public record WebSearchResult(String title, String url, String snippet, String publishedAt) {}
