package dev.mikoto2000.rei.conversation;

import java.time.OffsetDateTime;

public record ConversationLogEntry(
    String conversationId,
    String scope,
    String speaker,
    OffsetDateTime timestamp,
    String content) {
}
