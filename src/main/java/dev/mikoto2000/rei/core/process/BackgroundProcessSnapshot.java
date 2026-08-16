package dev.mikoto2000.rei.core.process;

import java.time.Instant;
import java.util.List;

public record BackgroundProcessSnapshot(
    String processId,
    long pid,
    BackgroundProcessStatus status,
    Integer exitCode,
    Instant startedAt,
    Instant endedAt,
    List<String> stdout,
    List<String> stderr,
    boolean found,
    String message) {
}
