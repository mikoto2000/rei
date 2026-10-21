package dev.mikoto2000.rei.conversation;

import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.llm.ConversationIds;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 履歴シェルのユースケースを実現する。
 *
 * <p>Read-only Shell boundary: never resolves ownership from AgentRunScope.
 */
@Service
public class HistoryShellService {
  /** 既定直近／既定一覧上限／既定検索上限。 */
  public static final int DEFAULT_LAST = 50, DEFAULT_LIST_LIMIT = 50, DEFAULT_SEARCH_LIMIT = 10;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 保存先。 */
  private final ConversationLogStore store;

  /** 検索。 */
  private final ConversationHistorySearchService search;

  /** 形式。 */
  private final HistoryFormatter format = new HistoryFormatter();

  /** 指定された値や利用先を使って、HistoryShellServiceを初期化する。 */
  public HistoryShellService(
      ProjectService projects,
      ConversationLogStore store,
      ConversationHistorySearchService search) {
    this.projects = projects;
    this.store = store;
    this.search = search;
  }

  /** 会話履歴と受付記録の管理で用いるプロジェクトを返す。 */
  private ProjectContext project(String reference) {
    var selected = projects.currentProject();
    var all = projects.registeredProjects();
    if (reference == null)
      return all.stream()
          .filter(p -> p.root().equals(selected))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalArgumentException("Project not found: " + projects.currentProject()));
    var byId = all.stream().filter(p -> p.id().equals(reference)).findFirst();
    if (byId.isPresent()) return byId.get();
    var matches = all.stream().filter(p -> p.name().equals(reference)).toList();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (matches.size() > 1)
      throw new IllegalArgumentException("Ambiguous project: " + reference + " (use ProjectId)");
    if (matches.isEmpty()) throw new IllegalArgumentException("Project not found: " + reference);
    return matches.getFirst();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public void list(String reference, int limit, int offset, Consumer<String> out) {
    positive(limit, 1000, "--limit");
    if (offset < 0) throw new IllegalArgumentException("--offset must be non-negative");
    var project = project(reference);
    out.accept(
        "Conversations in project: " + format.label(project.name()) + " [" + project.id() + "]");
    var rows = store.listConversations(project.id(), limit, offset);
    if (rows.isEmpty()) {
      out.accept("No conversations.");
      return;
    }
    out.accept("ID  UPDATED  MESSAGES");
    for (var row : rows)
      out.accept(
          format.label(format.logicalId(row.conversationId()))
              + "  "
              + format.timestamp(row.updatedAt().toInstant().toString())
              + "  "
              + row.messageCount());
    if (rows.size() == limit)
      out.accept(
          "Next page: /history list --project "
              + project.id()
              + " --limit "
              + limit
              + " --offset "
              + ((long) offset + limit));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  public void show(
      String reference, String conversation, int last, boolean all, Consumer<String> out) {
    positive(last, 10000, "--last");
    var project = project(reference);
    String selected = projects.currentSessionId();
    String logical =
        conversation == null
            ? (selected != null && selected.startsWith("project:" + project.id() + ":")
                ? selected
                : ConversationIds.chat())
            : conversation;
    String id = logical.startsWith("project:") ? logical : project.conversationId(logical);
    if (!id.startsWith("project:" + project.id() + ":"))
      throw new IllegalArgumentException("Conversation not found: " + logical);
    var recent = store.recentConversation(project.id(), id, all ? 1 : last);
    if (recent.isEmpty()) throw new IllegalArgumentException("Conversation not found: " + logical);
    out.accept("Project: " + format.label(project.name()) + " [" + project.id() + "]");
    out.accept("Conversation: " + format.label(format.logicalId(id)));
    out.accept("");
    if (all)
      store.visitProject(
          project.id(),
          entry -> {
            if (id.equals(entry.conversationId())) out.accept(format.message(entry));
          });
    else recent.forEach(entry -> out.accept(format.message(entry)));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public void search(String query, HistorySearchScope scope, int limit, Consumer<String> out) {
    positive(limit, 50, "--limit");
    var current = project(null);
    var results =
        search.searchForShell(
            new HistorySearchRequest(
                query, current.id(), null, scope, "all", null, null, null, limit));
    if (results.isEmpty()) {
      out.accept("No history matches.");
      return;
    }
    int index = 0;
    for (var result : results) {
      out.accept(
          ++index
              + ". ["
              + format.label(result.sourceProjectName())
              + " / "
              + format.label(format.logicalId(result.conversationId()))
              + "]");
      out.accept("   ProjectId: " + format.label(result.sourceProjectId()));
      out.accept(
          "   " + format.timestamp(result.timestamp()) + " " + format.label(result.speaker()));
      out.accept(format.body(result.content()));
    }
  }

  /** 会話履歴と受付記録の管理に関する正のを処理する。 */
  private void positive(int value, int max, String option) {
    if (value < 1 || value > max)
      throw new IllegalArgumentException(option + " must be between 1 and " + max);
  }
}
