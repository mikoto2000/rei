package dev.mikoto2000.rei.chatmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/** Exercises real SQLite write and commit contention, including rollback before retry. */
@Tag("integration")
class JdbcConversationMemoryRetryTest {
  @TempDir Path directory;
  private SQLiteDataSource source;
  private JdbcConversationMemoryStore store;
  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicInteger commits = new AtomicInteger();
  private final AtomicInteger rollbacks = new AtomicInteger();
  private SqlAction afterRollback = () -> {};
  private AbstractDataSource tracked;

  @BeforeEach
  void setUp() {
    var config = new SQLiteConfig();
    config.setBusyTimeout(0);
    source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + directory.resolve("memory.db"));
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("PRAGMA journal_mode=DELETE");
    jdbc.execute(
        "CREATE TABLE SPRING_AI_CHAT_MEMORY (conversation_id TEXT, content TEXT, type TEXT, timestamp TIMESTAMP, sequence_id INTEGER)");
    new JdbcConversationMemoryStore(source).saveAll("a", List.of(new UserMessage("old")));
    tracked =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            connections.incrementAndGet();
            var real = source.getConnection();
            var connection = spy(real);
            doAnswer(
                    call -> {
                      commits.incrementAndGet();
                      real.commit();
                      return null;
                    })
                .when(connection)
                .commit();
            doAnswer(
                    call -> {
                      rollbacks.incrementAndGet();
                      real.rollback();
                      afterRollback.run();
                      return null;
                    })
                .when(connection)
                .rollback();
            return connection;
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
          }
        };
    store = new JdbcConversationMemoryStore(tracked);
  }

  @Test
  void retriesCommitAfterRollbackAndSavesExactlyOnce() throws Exception {
    try (var reader = holdReader()) {
      afterRollback =
          () -> {
            assertThat(new JdbcConversationMemoryStore(source).findByConversationId("a"))
                .extracting(Message::getText)
                .containsExactly("old");
            reader.rollback();
          };
      store.saveAll("a", List.of(new UserMessage("new"), new UserMessage("second")));
      assertThat(commits).hasValue(2);
      assertThat(rollbacks).hasValue(1);
      assertThat(connections).hasValue(2);
      assertThat(store.findByConversationId("a"))
          .extracting(Message::getText)
          .containsExactly("new", "second");
    }
  }

  @Test
  void retriesWriteContentionInANewTransaction() throws Exception {
    try (var writer = source.getConnection();
        var statement = writer.createStatement()) {
      writer.setAutoCommit(false);
      statement.executeUpdate("UPDATE SPRING_AI_CHAT_MEMORY SET content='uncommitted'");
      afterRollback = writer::rollback;
      store.saveAll("a", List.of(new UserMessage("new")));
      assertThat(rollbacks).hasValue(1);
      assertThat(connections).hasValue(2);
      assertThat(store.findByConversationId("a"))
          .extracting(Message::getText)
          .containsExactly("new");
    }
  }

  @Test
  void persistentCommitContentionStopsAfterFourAttemptsAndPreservesOldRows() throws Exception {
    try (var reader = holdReader()) {
      assertThatThrownBy(() -> store.saveAll("a", List.of(new UserMessage("new"))))
          .hasRootCauseInstanceOf(org.sqlite.SQLiteException.class);
      assertThat(commits).hasValue(4);
      assertThat(rollbacks).hasValue(4);
      assertThat(connections).hasValue(4);
      reader.rollback();
    }
    assertThat(store.findByConversationId("a")).extracting(Message::getText).containsExactly("old");
  }

  @Test
  void nonContentionFailureIsNotRetried() {
    new JdbcTemplate(source).execute("DROP TABLE SPRING_AI_CHAT_MEMORY");
    assertThatThrownBy(() -> store.saveAll("a", List.of(new UserMessage("new"))))
        .hasRootCauseInstanceOf(org.sqlite.SQLiteException.class);
    assertThat(connections).hasValue(1);
  }

  @Test
  void interruptionPreventsRetriesAndRetainsInterruptFlag() throws Exception {
    try (var reader = holdReader()) {
      Thread.currentThread().interrupt();
      try {
        assertThatThrownBy(() -> store.saveAll("a", List.of(new UserMessage("new"))))
            .isInstanceOf(RuntimeException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(connections).hasValue(1);
      } finally {
        Thread.interrupted();
      }
      reader.rollback();
    }
  }

  @Test
  void participatingTransactionIsNotRetried() throws Exception {
    try (var writer = source.getConnection();
        var statement = writer.createStatement()) {
      writer.setAutoCommit(false);
      statement.executeUpdate("UPDATE SPRING_AI_CHAT_MEMORY SET content='uncommitted'");
      var outer = new TransactionTemplate(new DataSourceTransactionManager(tracked));
      assertThatThrownBy(() ->
              outer.executeWithoutResult(
                  status -> store.saveAll("a", List.of(new UserMessage("new")))))
          .hasRootCauseInstanceOf(org.sqlite.SQLiteException.class);
      assertThat(connections).hasValue(1);
      writer.rollback();
    }
  }

  private Connection holdReader() throws SQLException {
    var reader = source.getConnection();
    reader.setAutoCommit(false);
    try (var statement = reader.createStatement();
        var rows = statement.executeQuery("SELECT * FROM SPRING_AI_CHAT_MEMORY")) {
      assertThat(rows.next()).isTrue();
    }
    return reader;
  }

  @FunctionalInterface
  private interface SqlAction {
    void run() throws SQLException;
  }
}
