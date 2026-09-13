package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpmlParserTest {
  private final OpmlParser parser = new OpmlParser();

  @Test
  void parsesSingleFeedAndPreservesMetadata() {
    var feeds = parse("""
        <opml version="2.0"><body><outline type="rss" title="技術ブログ" text="Text"
          xmlUrl="https://example.jp/feed" htmlUrl="https://example.jp/"/></body></opml>
        """);
    assertEquals(1, feeds.size());
    var feed = feeds.getFirst();
    assertEquals("技術ブログ", feed.title());
    assertEquals("Text", feed.text());
    assertEquals("技術ブログ", feed.displayName());
    assertEquals("https://example.jp/feed", feed.xmlUrl());
    assertEquals("https://example.jp/", feed.htmlUrl());
  }

  @Test
  void traversesNestedOutlinesWithoutRequiringTypeAndUsesNameFallbacks() {
    var feeds = parse("""
        <opml><head><outline xmlUrl="ignored"/></head><body>
          <outline text="Development"><outline text="Java">
            <outline text="Example" xmlUrl="https://example.com/a"/>
          </outline></outline>
          <outline xmlUrl="https://example.com/b"/>
        </body></opml>
        """);
    assertEquals(2, feeds.size());
    assertEquals("Example", feeds.getFirst().displayName());
    assertEquals(List.of("Development", "Java"), feeds.getFirst().categories());
    assertEquals("https://example.com/b", feeds.getLast().displayName());
    assertTrue(feeds.getLast().categories().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> feeds.clear());
  }

  @Test
  void preservesInvalidUrlsForPerFeedFailureHandling() {
    var feeds = parse("<opml><body><outline xmlUrl=\"\"/><outline xmlUrl=\"not a url\"/></body></opml>");
    assertEquals(List.of("", "not a url"), feeds.stream().map(OpmlSubscription::xmlUrl).toList());
  }

  @Test
  void honorsXmlEncodingDeclaration() {
    String xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><opml><body><outline text=\"日本語\" xmlUrl=\"https://example.jp\"/></body></opml>";
    assertEquals("日本語", parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_16))).getFirst().displayName());
  }

  @ParameterizedTest
  @ValueSource(strings = {"<opml>", "<foo><bar/></foo>", "<opml><head/></opml>",
      "<opml><body/></opml>", "<opml><body><outline text='empty'/></body></opml>",
      "<!DOCTYPE opml><opml><body><outline xmlUrl='https://example.com'/></body></opml>",
      "<!DOCTYPE opml [<!ENTITY xxe SYSTEM 'file:///nonexistent-secret'>]><opml><body><outline xmlUrl='&xxe;'/></body></opml>",
      "<!DOCTYPE opml [<!ENTITY xxe SYSTEM 'file:///nonexistent-secret'>]><opml><body><outline xmlUrl='https://example.com'>&xxe;</outline></body></opml>",
      "<!DOCTYPE opml SYSTEM 'https://example.invalid/evil.dtd'><opml><body/></opml>",
      "<opml xmlns:xi='http://www.w3.org/2001/XInclude'><body><xi:include href='file:///secret'/></body></opml>"})
  void rejectsInvalidOrUnsafeDocuments(String xml) {
    assertThrows(OpmlImportException.class, () -> parse(xml));
  }

  @Test
  void rejectsExcessiveNesting() {
    assertThrows(OpmlImportException.class, () -> parse("<opml><body>" + "<outline>".repeat(300)
        + "<outline xmlUrl='https://example.com'/>" + "</outline>".repeat(300) + "</body></opml>"));
  }

  @Test
  void doesNotResolveXIncludeAlongsideValidFeed() {
    var feeds = parse("""
        <opml xmlns:xi="http://www.w3.org/2001/XInclude"><body>
          <xi:include href="file:///nonexistent-secret"/>
          <outline xmlUrl="https://example.com"/>
        </body></opml>
        """);
    assertEquals(1, feeds.size());
  }

  private List<OpmlSubscription> parse(String xml) {
    return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }
}
