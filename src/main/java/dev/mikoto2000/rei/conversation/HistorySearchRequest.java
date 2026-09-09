package dev.mikoto2000.rei.conversation;

/** Immutable search ownership, separate from the message category (conversationScope). */
public record HistorySearchRequest(String query, String preferredProjectId, String referencedProject,
    HistorySearchScope retrievalScope, String conversationScope, String speaker, String since, String until,
    Integer limit) {
  public HistorySearchRequest {
    if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
    if (preferredProjectId != null) java.util.UUID.fromString(preferredProjectId);
    if (retrievalScope == null) retrievalScope = HistorySearchScope.CURRENT_PROJECT_PREFERRED;
  }
}
