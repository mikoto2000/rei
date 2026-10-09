package dev.mikoto2000.rei.application.input;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Modality metadata never interprets the text as a CLI command. */
public record ConversationInput(UUID inputId, InputSource source, ConversationTarget target,
    String text, Instant createdAt) {
  public ConversationInput {
    Objects.requireNonNull(inputId); Objects.requireNonNull(source);
    Objects.requireNonNull(target); Objects.requireNonNull(createdAt);
    if (text == null || text.isBlank()) throw new IllegalArgumentException("Message required");
    if (source == InputSource.VOICE && (target.sessionId() == null || text.stripLeading().startsWith("/")))
      throw new IllegalArgumentException("Voice requires a captured Session and cannot execute slash commands");
  }
}