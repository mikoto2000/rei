package dev.mikoto2000.rei.conversation;

import java.util.List;

public record ConversationHistoryDetail(
    String conversationId,
    String scope,
    List<ConversationHistoryMessage> messages,
    String sourceProjectId,
    String sourceProjectName,
    String contextBoundary) {
  public ConversationHistoryDetail(String conversationId, String scope, List<ConversationHistoryMessage> messages) {
    this(conversationId, scope, messages, null, null, null);
  }
}
