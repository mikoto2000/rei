package dev.mikoto2000.rei.application.session;

import java.time.Instant;

public record SessionTurn(String runId, String userMessage, String assistantMessage, Instant createdAt) {}
