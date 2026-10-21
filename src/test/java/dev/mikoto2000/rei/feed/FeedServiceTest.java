package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** フィードサービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FeedServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 記事本文を取得できない場合の要約に備え、フィード内の説明と本文を保存することを確認する。 */
  @Test
  void savesEmbeddedFeedContentForSummaryFallback() {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", "Example");
    service.saveFetchedItems(
        feed.id(),
        List.of(
            new FetchedFeedItem(
                "Title",
                "https://example.com/article",
                OffsetDateTime.parse("2026-04-22T01:23:45Z"),
                "Description marker",
                "Embedded content marker")),
        OffsetDateTime.parse("2026-04-22T02:00:00Z"));

    FeedItem item = service.listItemsForFeed(feed.id()).getFirst();

    assertEquals("Description marker", item.description());
    assertEquals("Embedded content marker", item.content());
  }

  /** 登録した購読先が、有効なフィードとして同じ内容で一覧に現れることを確認する。 */
  @Test
  void addAndListFeeds() {
    FeedService service = newService();

    Feed created = service.add("https://example.com/feed.xml", "Example Feed");

    assertEquals(1, service.list().size());
    assertEquals(created, service.list().getFirst());
    assertTrue(created.enabled());
    assertEquals("https://example.com/feed.xml", created.url());
    assertEquals("Example Feed", created.displayName());
  }

  /** 同じフィードURLを二重に登録できないことを確認する。 */
  @Test
  void addRejectsDuplicateFeedUrl() {
    FeedService service = newService();
    service.add("https://example.com/feed.xml", "Example Feed");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.add("https://example.com/feed.xml", "Duplicate"));

    assertEquals("同じフィード URL は登録できません", error.getMessage());
  }

  /** 認証情報を含むURLを保存前に拒否し、エラーにも秘密情報を残さないことを確認する。 */
  @Test
  void rejectsCredentialUrlsBeforeDatabaseStorage() {
    FeedService service = newService();
    for (String url :
        List.of(
            "https://user:secret@example.com/feed.xml",
            "https://example.com/feed.xml?token=secret")) {
      var error = assertThrows(IllegalArgumentException.class, () -> service.add(url, "Private"));
      assertTrue(!error.toString().contains("secret"));
    }
    // 拒否したURLがエラーになるだけでなく、購読情報として保存されていないことも保証する。
    assertTrue(service.list().isEmpty());
  }

  /** 旧データに含まれた認証情報を参照時に隠し、そのURLへの取得要求も送らないことを確認する。 */
  @Test
  void legacyCredentialUrlsAreRedactedOnReadAndCannotBeFetched() {
    FeedService service = newService();
    Feed feed = service.add("https://example.com/feed.xml", null);
    var jdbc =
        org.springframework.jdbc.core.simple.JdbcClient.create(
            new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed.db")));
    jdbc.sql("UPDATE feeds SET url = ?, display_name = ? WHERE id = ?")
        .params(
            "https://user:legacy-secret@example.com/feed.xml",
            "https://user:legacy-secret@example.com/feed.xml",
            feed.id())
        .update();
    assertTrue(!service.findById(feed.id()).toString().contains("legacy-secret"));
    assertTrue(!service.list().toString().contains("legacy-secret"));
    var updater =
        new FeedUpdateService(
            service,
            new FeedFetcher(
                uri -> {
                  throw new AssertionError("must not send");
                }));
    assertTrue(!updater.update(feed.id()).toString().contains("legacy-secret"));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private FeedService newService() {
    return new FeedService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feed.db")));
  }
}
