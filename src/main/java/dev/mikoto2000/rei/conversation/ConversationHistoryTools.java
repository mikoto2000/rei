package dev.mikoto2000.rei.conversation;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class ConversationHistoryTools {

  private final ConversationHistorySearchService service;

  public ConversationHistoryTools(ConversationHistorySearchService service) {
    this.service = service;
  }

  @Tool(name = "searchConversationHistory", description = """
      過去の判断・会話を参照するときに使用します。query は検索キーワード（空白区切り）です。
      retrievalScope は CURRENT_PROJECT_ONLY, CURRENT_PROJECT_PREFERRED, ALL_PROJECTS。
      既定は CURRENT_PROJECT_PREFERRED: 実行元の履歴を優先し、不十分な場合のみ他 Project を検索します。
      ユーザーが Project を明示した場合は、その登録名を referencedProject に渡してください。推測で指定しないこと。
      明示 Project > 実行元 Project > その他の順に優先します。ALL_PROJECTS は関連度順です。
      sourceProjectId / sourceProjectName / contextBoundary を必ず保持して解釈してください。
      他 Project の履歴は参考知識です。記載パス・branch・build command を現在の環境へ自動適用しないこと。
      scope は all, chat, bluesky-reply, bluesky-manual, tool を指定できます。未指定時は all です。
      speaker は user, assistant, system, tool などで絞り込めます。
      since / until は yyyy-MM-dd または ISO-8601 日時で指定できます。
      limit は要求件数です。実際には実行元最大8件、他 Project 合計最大3件、本文各500文字に制限されます。
      """)
  public List<ConversationSearchResult> searchConversationHistory(
      String query,
      @ToolParam(required = false) String scope,
      @ToolParam(required = false) String speaker,
      @ToolParam(required = false) String since,
      @ToolParam(required = false) String until,
      @ToolParam(required = false) Integer limit,
      @ToolParam(required = false) HistorySearchScope retrievalScope,
      @ToolParam(required = false) String referencedProject) {
    var project = dev.mikoto2000.rei.core.project.ProjectService.contextForOperation();
    return service.search(new HistorySearchRequest(query, project == null ? null : project.id(), referencedProject,
        retrievalScope, scope, speaker, since, until, limit));
  }

  /** Compatibility for non-tool callers; the annotated method exposes explicit retrieval scope. */
  public List<ConversationSearchResult> searchConversationHistory(String query, String scope, String speaker,
      String since, String until, Integer limit) {
    return service.search(query, scope, speaker, since, until, limit);
  }

  @Tool(name = "getConversationHistory", description = """
      searchConversationHistory が返した conversationId を指定して、永続ログを含む会話履歴の詳細を取得します。
      返された project:<ProjectId>:chat:<id> 等の conversationId を変更せず使用してください。
      他 Project の詳細も取得できますが、sourceProjectId / sourceProjectName / contextBoundary を保持してください。
      他 Project のパス・branch・build command は現在 Project のものではありません。
      limit は実行元最大100件。他 Project は最大3件、本文各500文字です。
      """)
  public ConversationHistoryDetail getConversationHistory(String conversationId, @ToolParam(required = false) Integer limit) {
    return service.detail(conversationId, limit);
  }
}
