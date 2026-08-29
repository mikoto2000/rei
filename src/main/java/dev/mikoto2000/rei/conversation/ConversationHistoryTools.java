package dev.mikoto2000.rei.conversation;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryTools {

  private final ConversationHistorySearchService service;

  public ConversationHistoryTools(ConversationHistorySearchService service) {
    this.service = service;
  }

  @Tool(name = "searchConversationHistory", description = """
      永続化された会話ログを含む会話履歴を検索します。query は必須です。
      scope は all, chat, bluesky-reply, bluesky-manual, tool を指定できます。未指定時は all です。
      speaker は user, assistant, system, tool などで絞り込めます。
      since / until は yyyy-MM-dd または ISO-8601 日時で指定できます。
      limit は最大 50 件です。
      """)
  public List<ConversationSearchResult> searchConversationHistory(
      String query,
      String scope,
      String speaker,
      String since,
      String until,
      Integer limit) {
    return service.search(query, scope, speaker, since, until, limit);
  }

  @Tool(name = "getConversationHistory", description = """
      searchConversationHistory が返した conversationId を指定して、永続ログを含む会話履歴の詳細を取得します。
      conversationId は chat:<id>, bluesky-reply:<handle>, bluesky-manual:<id>, tool:<name> 形式です。
      limit は最大 100 件です。
      """)
  public ConversationHistoryDetail getConversationHistory(String conversationId, Integer limit) {
    return service.detail(conversationId, limit);
  }
}
