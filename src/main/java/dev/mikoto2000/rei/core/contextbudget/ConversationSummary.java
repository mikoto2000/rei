package dev.mikoto2000.rei.core.contextbudget;

import java.time.Instant;

/** Cursor is scoped to its conversation (turn sequence) or run (message sequence), never an event bus sequence. */
public record ConversationSummary(String summary, long throughSequence, Instant updatedAt) {
  public static ConversationSummary empty() { return new ConversationSummary("", 0, Instant.EPOCH); }
}
