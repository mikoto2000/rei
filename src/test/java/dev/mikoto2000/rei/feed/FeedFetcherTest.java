package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class FeedFetcherTest {

  @Test
  void fetchParsesRssFeed() {
    FeedFetcher fetcher = new FeedFetcher(uri -> new FeedHttpResponse(200, """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>Example RSS</title>
            <link>https://example.com/</link>
            <description>Example Description</description>
            <item>
              <title>First Post</title>
              <link>https://example.com/posts/1</link>
              <pubDate>Tue, 21 Apr 2026 09:30:00 GMT</pubDate>
            </item>
            <item>
              <title>Second Post</title>
              <link>https://example.com/posts/2</link>
            </item>
          </channel>
        </rss>
        """));

    FetchedFeed fetched = fetcher.fetch("https://example.com/feed.xml");

    assertEquals("Example RSS", fetched.title());
    assertEquals("https://example.com/", fetched.siteUrl());
    assertEquals("Example Description", fetched.description());
    assertEquals(2, fetched.items().size());
    assertEquals("First Post", fetched.items().getFirst().title());
    assertEquals("https://example.com/posts/1", fetched.items().getFirst().url());
    assertEquals(OffsetDateTime.parse("2026-04-21T09:30:00Z"), fetched.items().getFirst().publishedAt());
    assertNull(fetched.items().get(1).publishedAt());
  }

  @Test
  void fetchParsesAtomFeed() {
    FeedFetcher fetcher = new FeedFetcher(uri -> new FeedHttpResponse(200, """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <title>Example Atom</title>
          <link href="https://example.com/"/>
          <subtitle>Atom Description</subtitle>
          <entry>
            <title>Atom Entry</title>
            <link href="https://example.com/entries/1"/>
            <published>2026-04-22T01:23:45Z</published>
          </entry>
        </feed>
        """));

    FetchedFeed fetched = fetcher.fetch("https://example.com/atom.xml");

    assertEquals("Example Atom", fetched.title());
    assertEquals("https://example.com/", fetched.siteUrl());
    assertEquals("Atom Description", fetched.description());
    assertEquals(List.of(new FetchedFeedItem("Atom Entry", "https://example.com/entries/1", OffsetDateTime.parse("2026-04-22T01:23:45Z"))),
        fetched.items());
  }

  @Test
  void fetchRaisesStatusErrorForNonSuccessResponse() {
    FeedFetcher fetcher = new FeedFetcher(uri -> new FeedHttpResponse(502, "bad gateway"));

    FeedFetchException error = org.junit.jupiter.api.Assertions.assertThrows(FeedFetchException.class,
        () -> fetcher.fetch("https://example.com/feed.xml"));

    assertEquals(502, error.httpStatus());
  }
}
