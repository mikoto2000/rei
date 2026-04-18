package dev.mikoto2000.rei.interest;

import java.time.OffsetDateTime;

public record ConversationSnippet(
    String conversationId,
    String text,
    OffsetDateTime createdAt) {
}
