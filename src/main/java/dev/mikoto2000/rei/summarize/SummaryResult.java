package dev.mikoto2000.rei.summarize;

import java.net.URI;

public record SummaryResult(URI uri, String summary, SummaryMetrics metrics) {
}
