package dev.mikoto2000.rei.event;

public record ContextCompressionPayload(
    long beforeEstimatedTokens,
    long afterEstimatedTokens,
    int compressedMessageCount,
    long summaryThroughSequence,
    String reason,
    String details)
    implements AgentEventPayload {
  public ContextCompressionPayload(
      long beforeEstimatedTokens,
      long afterEstimatedTokens,
      int compressedMessageCount,
      long summaryThroughSequence,
      String reason) {
    this(
        beforeEstimatedTokens,
        afterEstimatedTokens,
        compressedMessageCount,
        summaryThroughSequence,
        reason,
        null);
  }
}
