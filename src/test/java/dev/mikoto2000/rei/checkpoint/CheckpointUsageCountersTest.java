package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

@Tag("integration")
class CheckpointUsageCountersTest {
  @TempDir Path root;

  private SQLiteDataSource source() {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + root.resolve("usage.db"));
    return source;
  }

  private void matchesScan(PersistentCheckpointRepository repo) throws Exception {
    try (var c = source().getConnection();
        var s = c.createStatement();
        var rows =
            s.executeQuery(
                "SELECT COUNT(*),COALESCE(SUM(length(CAST(snapshot AS BLOB))),0),(SELECT COUNT(*) FROM checkpoint_event_keys) FROM checkpoint_revisions")) {
      assertTrue(rows.next());
      var usage = repo.historyUsage();
      assertEquals(rows.getLong(1), usage.revisions());
      assertEquals(rows.getLong(2), usage.snapshotBytes());
      assertEquals(rows.getLong(3), usage.eventKeys());
    }
  }

  @Test
  void migrationRestartUnicodeUpdatesAndDeletesMatchAuthoritativeRows() throws Exception {
    var settings = new CheckpointProperties();
    var repo = new PersistentCheckpointRepository(source(), settings);
    var state = PersistentCheckpoint.initial("task", "project", "session", "run", root, "日本語😀");
    repo.save(state, 0, "one");
    // Simulate a pre-counter database, then bootstrap its existing rows.
    try (var c = source().getConnection();
        var s = c.createStatement()) {
      for (var name :
          new String[] {
            "checkpoint_usage_insert",
            "checkpoint_usage_delete",
            "checkpoint_usage_update",
            "checkpoint_keys_insert",
            "checkpoint_keys_delete"
          }) s.execute("DROP TRIGGER " + name);
      s.execute("DROP TABLE checkpoint_history_usage");
    }
    repo = new PersistentCheckpointRepository(source(), settings);
    matchesScan(repo);
    repo.save(state, 1, "two");
    repo.save(state, 0, "one");
    matchesScan(new PersistentCheckpointRepository(source(), settings));
    try (var c = source().getConnection();
        var s = c.createStatement()) {
      s.executeUpdate("UPDATE checkpoint_revisions SET snapshot=snapshot||'日本語😀'");
      s.executeUpdate("DELETE FROM checkpoint_revisions WHERE revision=1");
      s.executeUpdate("DELETE FROM checkpoint_event_keys WHERE event='one'");
    }
    matchesScan(repo);
  }

  @Test
  void rollbackAndForcedCleanupPreserveCountsAndReplayKeys() throws Exception {
    var repo = new PersistentCheckpointRepository(source(), new CheckpointProperties());
    var state = PersistentCheckpoint.initial("task", "project", "session", "run", root, "request");
    repo.save(state, 0, "one");
    try (var c = source().getConnection();
        var s = c.createStatement()) {
      c.setAutoCommit(false);
      s.executeUpdate("DELETE FROM checkpoint_revisions");
      s.executeUpdate("DELETE FROM checkpoint_event_keys");
      c.rollback();
    }
    matchesScan(repo);
    assertEquals(1, repo.historyUsage().revisions());
    repo.cleanupHistory(true, true);
    matchesScan(repo);
    assertEquals(0, repo.historyUsage().snapshotBytes());
    assertEquals(1, repo.historyUsage().eventKeys());
  }
}
