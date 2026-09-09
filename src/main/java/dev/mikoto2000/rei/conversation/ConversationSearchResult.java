package dev.mikoto2000.rei.conversation;

public record ConversationSearchResult(
    String conversationId,
    String scope,
    String speaker,
    String timestamp,
    String summary,
    String content,
    String sourceProjectId,
    String sourceProjectName,
    String contextBoundary,
    double relevanceScore) {
  public ConversationSearchResult(String conversationId, String scope, String speaker, String timestamp,
      String summary, String content) {
    this(conversationId, scope, speaker, timestamp, summary, content, null, null, null, 0);
  }
}
