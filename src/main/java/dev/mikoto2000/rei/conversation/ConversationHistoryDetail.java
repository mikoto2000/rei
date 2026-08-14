package dev.mikoto2000.rei.conversation;

import java.util.List;

public record ConversationHistoryDetail(
    String conversationId,
    String scope,
    List<ConversationHistoryMessage> messages) {
}
