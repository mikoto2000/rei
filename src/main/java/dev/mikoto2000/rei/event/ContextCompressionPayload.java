package dev.mikoto2000.rei.event;

public record ContextCompressionPayload(long beforeEstimatedTokens, long afterEstimatedTokens,
    int compressedMessageCount, long summaryThroughSequence, String reason) implements AgentEventPayload { }
