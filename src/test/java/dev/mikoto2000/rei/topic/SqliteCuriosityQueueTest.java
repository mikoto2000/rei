package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class SqliteCuriosityQueueTest {

  @TempDir
  Path tempDir;

  @Test
  void persistsCuriosityItemsAcrossQueueInstances() {
    Path dbPath = tempDir.resolve(".rei").resolve("curiosity.db");
    SqliteCuriosityQueue first = new SqliteCuriosityQueue(dataSource(dbPath));
    first.add(item("id-1", "Working Set 測定", 0.8, null));

    SqliteCuriosityQueue second = new SqliteCuriosityQueue(dataSource(dbPath));

    assertEquals(1, second.findCandidates(new CuriosityQuery(now(), 10)).size());
    assertEquals("Working Set 測定", second.findCandidates(new CuriosityQuery(now(), 10)).getFirst().question());
  }

  @Test
  void duplicateQuestionIsSuppressedByNormalizedUniqueKey() {
    SqliteCuriosityQueue queue = new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("id-1", "Working Set 測定", 0.8, null));
    queue.add(item("id-2", " working  set 測定 ", 0.9, null));

    assertEquals(1, queue.findCandidates(new CuriosityQuery(now(), 10)).size());
  }

  @Test
  void statusUpdatesRemoveItemsFromPendingCandidates() {
    SqliteCuriosityQueue queue = new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("used", "used question", 0.8, null));
    queue.add(item("dismissed", "dismissed question", 0.7, null));

    queue.markUsed("used");
    queue.dismiss("dismissed");

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  @Test
  void expiredItemsAreMarkedAndNotReturned() {
    SqliteCuriosityQueue queue = new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("expired", "expired question", 0.9, Instant.parse("2026-09-01T00:00:00Z")));

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  @Test
  void candidatesAreOrderedByPriorityAndLimited() {
    SqliteCuriosityQueue queue = new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("low", "low question", 0.1, null));
    queue.add(item("high", "high question", 0.9, null));

    var candidates = queue.findCandidates(new CuriosityQuery(now(), 1));

    assertEquals(1, candidates.size());
    assertEquals("high", candidates.getFirst().id());
  }

  private DataSource dataSource(Path path) {
    try {
      java.nio.file.Files.createDirectories(path.toAbsolutePath().getParent());
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + path);
    return dataSource;
  }

  private CuriosityItem item(String id, String question, double priority, Instant expiresAt) {
    return new CuriosityItem(id, question, "reason", TopicSource.CONVERSATION, priority,
        Instant.parse("2026-09-02T00:00:00Z"), expiresAt, CuriosityStatus.PENDING);
  }

  private Instant now() {
    return Instant.parse("2026-09-02T00:00:00Z");
  }
}
