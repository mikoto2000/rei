package dev.mikoto2000.rei.summarize;

/**
 * 情報の要約で受け渡す要約計測値を表す。
 *
 * @param fetchDurationMillis 取得継続時間ミリ秒数
 * @param extractDurationMillis 抽出継続時間ミリ秒数
 * @param llmDurationMillis 言語モデル継続時間ミリ秒数
 * @param totalDurationMillis 合計継続時間ミリ秒数
 * @param inputChars 入力文字数
 */
public record SummaryMetrics(
    long fetchDurationMillis,
    long extractDurationMillis,
    long llmDurationMillis,
    long totalDurationMillis,
    int inputChars) {}
