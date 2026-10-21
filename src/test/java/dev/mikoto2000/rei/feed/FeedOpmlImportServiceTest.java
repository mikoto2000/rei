package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** フィードOPML取り込みサービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FeedOpmlImportServiceTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void registersAllFeedsThroughExistingServiceWithoutFetching() throws Exception {
    FeedService feeds = spy(newService());
    var result =
        importer(feeds)
            .importFile(
                file(
                    "<outline title='日本語' xmlUrl='https://example.com/a'/><outline xmlUrl='https://example.com/b'/>"));
    assertEquals(2, result.imported().size());
    assertTrue(result.skipped().isEmpty());
    assertTrue(result.failed().isEmpty());
    verify(feeds).add("https://example.com/a", "日本語");
    verify(feeds).add("https://example.com/b", "https://example.com/b");
    assertEquals(2, feeds.list().size());
    for (Feed feed : feeds.list()) {
      assertNull(feed.lastFetchedAt());
      assertTrue(feeds.listItemsForFeed(feed.id()).isEmpty());
    }
    assertThrows(UnsupportedOperationException.class, () -> result.imported().clear());
  }

  /** フィードの購読と収集で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void continuesAfterInvalidUrlsDuplicatesAndRegistrationErrors() throws Exception {
    FeedService feeds = spy(newService());
    feeds.add("https://example.com/existing", "Existing");
    doThrow(new IllegalStateException("database unavailable"))
        .when(feeds)
        .add("https://example.com/error", "https://example.com/error");
    var result =
        importer(feeds)
            .importFile(
                file(
                    """
        <outline xmlUrl='https://example.com/a'/>
        <outline xmlUrl='not a url'/><outline xmlUrl=''/><outline xmlUrl='file:///secret'/>
        <outline xmlUrl='https://'/><outline xmlUrl='https://example.com/error'/>
        <outline xmlUrl='https://example.com/existing'/><outline xmlUrl='https://example.com/a'/>
        <outline xmlUrl='https://example.com/b'/>
        """));
    assertEquals(2, result.imported().size());
    assertEquals(2, result.skipped().size());
    assertEquals(5, result.failed().size());
    assertEquals("already registered", result.skipped().getFirst().reason());
    assertEquals("invalid feed URL", result.failed().getFirst().reason());
    assertEquals("feed registration failed", result.failed().getLast().reason());
    assertEquals(3, feeds.list().size());
  }

  /** フィードの購読と収集で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateUsesTypedExistingRegistrationError() {
    FeedService feeds = newService();
    feeds.add("https://example.com", "Example");
    assertThrows(DuplicateFeedException.class, () -> feeds.add("https://example.com", "Again"));
  }

  /** フィードの購読と収集で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedDocumentRegistersNothing() throws Exception {
    FeedService feeds = mock(FeedService.class);
    Path path = tempDir.resolve("broken.opml");
    Files.writeString(path, "<opml><body><outline xmlUrl='https://example.com'/>");
    assertThrows(OpmlImportException.class, () -> importer(feeds).importFile(path));
    verifyNoInteractions(feeds);
  }

  /** 未指定ファイルディレクトリを受け付けないことを確認する。 */
  @Test
  void rejectsMissingFileDirectoryAndOversizeFile() throws Exception {
    var importer = importer(mock(FeedService.class));
    assertTrue(
        assertThrows(
                OpmlImportException.class, () -> importer.importFile(tempDir.resolve("missing")))
            .getMessage()
            .contains("file not found"));
    assertTrue(
        assertThrows(OpmlImportException.class, () -> importer.importFile(tempDir))
            .getMessage()
            .contains("regular file"));
    Path large = tempDir.resolve("large.opml");
    Files.write(large, new byte[FeedOpmlImportService.MAX_FILE_SIZE_BYTES + 1]);
    assertTrue(
        assertThrows(OpmlImportException.class, () -> importer.importFile(large))
            .getMessage()
            .contains("10 MiB"));
  }

  /** 読み取りアクセス拒否されたを伝えることを確認する。 */
  @Test
  void reportsReadAccessDenied() {
    Path path = tempDir.resolve("denied.opml");
    try (var files = mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(path)).thenReturn(true);
      files
          .when(() -> Files.newInputStream(path))
          .thenThrow(new AccessDeniedException(path.toString()));
      assertTrue(
          assertThrows(
                  OpmlImportException.class,
                  () -> importer(mock(FeedService.class)).importFile(path))
              .getMessage()
              .contains("cannot read file"));
    }
  }

  /** フィードの購読と収集で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void boundsActualReadEvenWhenFileGrowsAfterSizeCheck() {
    Path path = tempDir.resolve("growing.opml");
    FeedService feeds = mock(FeedService.class);
    try (var files = mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(path)).thenReturn(true);
      files.when(() -> Files.size(path)).thenReturn(1L);
      files
          .when(() -> Files.newInputStream(path))
          .thenReturn(
              new java.io.ByteArrayInputStream(
                  new byte[FeedOpmlImportService.MAX_FILE_SIZE_BYTES + 1]));
      assertTrue(
          assertThrows(OpmlImportException.class, () -> importer(feeds).importFile(path))
              .getMessage()
              .contains("10 MiB"));
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(feeds);
    }
  }

  /** フィードの購読と収集で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void rejectsAndRedactsCredentialUrlsInImportResults() throws Exception {
    FeedService feeds = newService();
    var result =
        importer(feeds)
            .importFile(
                file(
                    "<outline title='secret' text='secret' htmlUrl='https://secret@example.com' xmlUrl='https://user:secret@example.com/feed'/><outline xmlUrl='https://example.com/feed?token=secret'/>"));
    assertEquals(2, result.failed().size());
    assertTrue(feeds.list().isEmpty());
    assertFalse(result.toString().contains("secret"));
  }

  /** {@code file} の条件を再現するための補助情報を返す。 */
  private Path file(String outlines) throws Exception {
    Path path = tempDir.resolve("feeds.opml");
    return Files.writeString(path, "<opml version='2.0'><body>" + outlines + "</body></opml>");
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private FeedService newService() {
    return new FeedService(
        new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feeds.db")));
  }

  /** {@code importer} の条件を再現するための補助情報を返す。 */
  private FeedOpmlImportService importer(FeedService feeds) {
    return new FeedOpmlImportService(new OpmlParser(), feeds);
  }
}
