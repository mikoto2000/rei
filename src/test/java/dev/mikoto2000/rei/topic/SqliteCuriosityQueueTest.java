package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

/** SQLite情報への関心実行待ちの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SqliteCuriosityQueueTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 話題の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void persistsCuriosityItemsAcrossQueueInstances() {
    Path dbPath = tempDir.resolve(".rei").resolve("curiosity.db");
    SqliteCuriosityQueue first = new SqliteCuriosityQueue(dataSource(dbPath));
    first.add(item("id-1", "Working Set 測定", 0.8, null));

    SqliteCuriosityQueue second = new SqliteCuriosityQueue(dataSource(dbPath));

    assertEquals(1, second.findCandidates(new CuriosityQuery(now(), 10)).size());
    assertEquals(
        "Working Set 測定",
        second.findCandidates(new CuriosityQuery(now(), 10)).getFirst().question());
  }

  /** 話題の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateQuestionIsSuppressedByNormalizedUniqueKey() {
    SqliteCuriosityQueue queue =
        new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("id-1", "Working Set 測定", 0.8, null));
    queue.add(item("id-2", " working  set 測定 ", 0.9, null));

    assertEquals(1, queue.findCandidates(new CuriosityQuery(now(), 10)).size());
  }

  /** 項目一覧を取り除くことを確認する。 */
  @Test
  void statusUpdatesRemoveItemsFromPendingCandidates() {
    SqliteCuriosityQueue queue =
        new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("used", "used question", 0.8, null));
    queue.add(item("dismissed", "dismissed question", 0.7, null));

    queue.markUsed("used");
    queue.dismiss("dismissed");

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  /** 話題の管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void expiredItemsAreMarkedAndNotReturned() {
    SqliteCuriosityQueue queue =
        new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("expired", "expired question", 0.9, Instant.parse("2026-09-01T00:00:00Z")));

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  /** 話題の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void candidatesAreOrderedByPriorityAndLimited() {
    SqliteCuriosityQueue queue =
        new SqliteCuriosityQueue(dataSource(tempDir.resolve("curiosity.db")));
    queue.add(item("low", "low question", 0.1, null));
    queue.add(item("high", "high question", 0.9, null));

    var candidates = queue.findCandidates(new CuriosityQuery(now(), 1));

    assertEquals(1, candidates.size());
    assertEquals("high", candidates.getFirst().id());
  }

  /** {@code dataSource} の条件を再現するための補助情報を返す。 */
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

  /** {@code item} の条件を再現するための補助情報を返す。 */
  private CuriosityItem item(String id, String question, double priority, Instant expiresAt) {
    return new CuriosityItem(
        id,
        question,
        "reason",
        TopicSource.CONVERSATION,
        priority,
        Instant.parse("2026-09-02T00:00:00Z"),
        expiresAt,
        CuriosityStatus.PENDING);
  }

  /** {@code now} の条件を再現するための補助情報を返す。 */
  private Instant now() {
    return Instant.parse("2026-09-02T00:00:00Z");
  }
}
