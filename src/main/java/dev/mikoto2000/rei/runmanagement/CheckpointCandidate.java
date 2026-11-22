package dev.mikoto2000.rei.runmanagement;

import java.time.Instant;

/** A read-only resume candidate with its owning project. */
public record CheckpointCandidate(
    String projectId,
    String projectName,
    String taskId,
    String status,
    String request,
    long revision,
    Instant savedAt) {}
