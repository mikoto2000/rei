package dev.mikoto2000.rei.summarize;

public record SummaryMetrics(
    long fetchDurationMillis,
    long extractDurationMillis,
    long llmDurationMillis,
    long totalDurationMillis,
    int inputChars) {
}
