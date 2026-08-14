package dev.mikoto2000.rei.conversation;

public record ConversationSearchResult(
    String conversationId,
    String scope,
    String speaker,
    String timestamp,
    String summary,
    String content) {
}
