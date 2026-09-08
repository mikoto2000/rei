package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.mikoto2000.rei.conversation.*;
import static org.assertj.core.api.Assertions.*;

class ProjectHistorySearchTest {
  @TempDir Path temp;
  @Test void scopedSearchDoesNotFallBackToUnownedGlobalHistory() throws Exception {
    String prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    try {
      var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("data/projects.json")));
      var ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + temp.resolve("history.db"));
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute("CREATE TABLE SPRING_AI_CHAT_MEMORY(conversation_id TEXT, content TEXT, type TEXT, timestamp BIGINT)");
      jdbc.update("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES('chat:main','needle foreign','USER',0)");
      var logs = new ConversationLogStore();
      logs.append(projects.currentContext().conversationId("chat:main"), "user", "needle owned");
      var search = new ConversationHistorySearchService(ds, logs);
      assertThat(search.search("needle", "chat", null, null, null, 10)).extracting(ConversationSearchResult::content)
          .containsExactly("needle owned");
      assertThat(search.detail("chat:main", 10).messages()).extracting(ConversationHistoryMessage::content)
          .containsExactly("needle owned");
    } finally {
      if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior);
    }
  }
}
