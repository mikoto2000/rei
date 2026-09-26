package dev.mikoto2000.rei.memory.service;

public record SleepRun(String id, String sessionId, String projectId, String startedAt, String completedAt,
    String status, long fromSequence, long toSequence, int processedTurns, int candidateCount,
    int added, int updated, int merged, int superseded, int ignored, int conflicts, int failed) { }
