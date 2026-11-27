package dev.mikoto2000.rei.autonomous;

import java.time.Instant;

/** Immutable facts evaluated before the isolated generator is called. Contains no credentials. */
public record AutonomousActionContext(
    Instant now, String targetDid, String targetHandle, boolean stopping,
    boolean activeOpportunity, int pendingDrafts, int generatedToday, Instant lastAttemptAt) {}
