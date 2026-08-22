package dev.mikoto2000.rei.ui.projection;

import java.time.Instant;

public record ToolExecutionView(
    String toolCallId,
    String toolName,
    ToolExecutionStatus status,
    String argumentsSummary,
    String resultSummary,
    ErrorView error,
    Long durationMillis,
    Instant startedAt,
    Instant completedAt) {
}
