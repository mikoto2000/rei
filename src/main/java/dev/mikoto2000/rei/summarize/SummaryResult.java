package dev.mikoto2000.rei.summarize;

import java.net.URI;

/**
 * 情報の要約で受け渡す要約結果を表す。
 *
 * @param uri 参照先のURI
 * @param summary 要約
 * @param metrics 計測値
 */
public record SummaryResult(URI uri, String summary, SummaryMetrics metrics) {}
