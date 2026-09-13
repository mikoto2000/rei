package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FeedOpmlImportServiceTest {
  @TempDir Path tempDir;

  @Test
  void registersAllFeedsThroughExistingServiceWithoutFetching() throws Exception {
    FeedService feeds = spy(newService());
    var result = importer(feeds).importFile(file("<outline title='日本語' xmlUrl='https://example.com/a'/><outline xmlUrl='https://example.com/b'/>"));
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

  @Test
  void continuesAfterInvalidUrlsDuplicatesAndRegistrationErrors() throws Exception {
    FeedService feeds = spy(newService());
    feeds.add("https://example.com/existing", "Existing");
    doThrow(new IllegalStateException("database unavailable")).when(feeds).add("https://example.com/error", "https://example.com/error");
    var result = importer(feeds).importFile(file("""
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

  @Test
  void duplicateUsesTypedExistingRegistrationError() {
    FeedService feeds = newService();
    feeds.add("https://example.com", "Example");
    assertThrows(DuplicateFeedException.class, () -> feeds.add("https://example.com", "Again"));
  }

  @Test
  void malformedDocumentRegistersNothing() throws Exception {
    FeedService feeds = mock(FeedService.class);
    Path path = tempDir.resolve("broken.opml");
    Files.writeString(path, "<opml><body><outline xmlUrl='https://example.com'/>");
    assertThrows(OpmlImportException.class, () -> importer(feeds).importFile(path));
    verifyNoInteractions(feeds);
  }

  @Test
  void rejectsMissingFileDirectoryAndOversizeFile() throws Exception {
    var importer = importer(mock(FeedService.class));
    assertTrue(assertThrows(OpmlImportException.class, () -> importer.importFile(tempDir.resolve("missing"))).getMessage().contains("file not found"));
    assertTrue(assertThrows(OpmlImportException.class, () -> importer.importFile(tempDir)).getMessage().contains("regular file"));
    Path large = tempDir.resolve("large.opml");
    Files.write(large, new byte[FeedOpmlImportService.MAX_FILE_SIZE_BYTES + 1]);
    assertTrue(assertThrows(OpmlImportException.class, () -> importer.importFile(large)).getMessage().contains("10 MiB"));
  }

  @Test
  void reportsReadAccessDenied() {
    Path path = tempDir.resolve("denied.opml");
    try (var files = mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(path)).thenReturn(true);
      files.when(() -> Files.newInputStream(path)).thenThrow(new AccessDeniedException(path.toString()));
      assertTrue(assertThrows(OpmlImportException.class, () -> importer(mock(FeedService.class)).importFile(path))
          .getMessage().contains("cannot read file"));
    }
  }

  @Test
  void boundsActualReadEvenWhenFileGrowsAfterSizeCheck() {
    Path path = tempDir.resolve("growing.opml");
    FeedService feeds = mock(FeedService.class);
    try (var files = mockStatic(Files.class)) {
      files.when(() -> Files.isRegularFile(path)).thenReturn(true);
      files.when(() -> Files.size(path)).thenReturn(1L);
      files.when(() -> Files.newInputStream(path)).thenReturn(
          new java.io.ByteArrayInputStream(new byte[FeedOpmlImportService.MAX_FILE_SIZE_BYTES + 1]));
      assertTrue(assertThrows(OpmlImportException.class, () -> importer(feeds).importFile(path))
          .getMessage().contains("10 MiB"));
      verifyNoInteractions(feeds);
    }
  }

  private Path file(String outlines) throws Exception {
    Path path = tempDir.resolve("feeds.opml");
    return Files.writeString(path, "<opml version='2.0'><body>" + outlines + "</body></opml>");
  }

  private FeedService newService() {
    return new FeedService(new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("feeds.db")));
  }

  private FeedOpmlImportService importer(FeedService feeds) {
    return new FeedOpmlImportService(new OpmlParser(), feeds);
  }
}
