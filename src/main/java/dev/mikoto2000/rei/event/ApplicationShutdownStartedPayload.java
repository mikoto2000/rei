package dev.mikoto2000.rei.event;

/** Process-wide lifecycle notification, independent of projects and runs. */
public record ApplicationShutdownStartedPayload(String reason) implements AgentEventPayload {}
