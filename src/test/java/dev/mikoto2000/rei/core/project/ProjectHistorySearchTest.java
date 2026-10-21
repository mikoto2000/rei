package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.conversation.ConversationHistoryMessage;
import dev.mikoto2000.rei.conversation.ConversationHistorySearchService;
import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.conversation.ConversationSearchResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/** プロジェクト履歴検索の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectHistorySearchTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** プロジェクトごとの作業環境で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void scopedSearchDoesNotFallBackToUnownedGlobalHistory() throws Exception {
    String prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    try {
      var projects =
          connect(
              new ProjectService(temp, new ProjectRegistry(temp.resolve("data/projects.json"))));
      var ds = new SQLiteDataSource();
      ds.setUrl("jdbc:sqlite:" + temp.resolve("history.db"));
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          "CREATE TABLE SPRING_AI_CHAT_MEMORY(conversation_id TEXT, content TEXT, type TEXT, timestamp BIGINT)");
      jdbc.update(
          "INSERT INTO SPRING_AI_CHAT_MEMORY VALUES('chat:main','needle foreign','USER',0)");
      var logs = new ConversationLogStore();
      logs.append(projects.currentContext().conversationId("chat:main"), "user", "needle owned");
      var search = new ConversationHistorySearchService(ds, logs);
      assertThat(search.search("needle", "chat", null, null, null, 10))
          .extracting(ConversationSearchResult::content)
          .containsExactly("needle owned");
      assertThat(search.detail("chat:main", 10).messages())
          .extracting(ConversationHistoryMessage::content)
          .containsExactly("needle owned");
    } finally {
      if (prior == null) System.clearProperty("rei.data-dir");
      else System.setProperty("rei.data-dir", prior);
    }
  }
}
