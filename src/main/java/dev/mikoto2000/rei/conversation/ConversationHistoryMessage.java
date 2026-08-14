package dev.mikoto2000.rei.conversation;

public record ConversationHistoryMessage(
    String speaker,
    String timestamp,
    String content) {
}
