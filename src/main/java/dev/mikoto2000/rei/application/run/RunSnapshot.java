package dev.mikoto2000.rei.application.run;

import java.time.Instant;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

public record RunSnapshot(AgentRunContext context, RunStatus status, Instant startedAt,
    Instant completedAt, RunFailure failure) {}
