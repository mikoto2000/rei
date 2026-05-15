package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultBlueskyApiClientTest {

  @Test
  void extractLinkFacetsReturnsUtf8ByteIndexes() {
    String text = "日本語 https://example.com/abc test";
    List<DefaultBlueskyApiClient.LinkFacet> facets = DefaultBlueskyApiClient.extractLinkFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.LinkFacet facet = facets.getFirst();
    assertEquals("https://example.com/abc", facet.uri());
    assertEquals("日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(facet.byteStart() + "https://example.com/abc".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  @Test
  void createRecordRequestBodyIncludesFacetsWhenUrlExists() {
    String body = DefaultBlueskyApiClient.createRecordRequestBody(
        "did:plc:abc",
        "Check this https://example.com/page.",
        OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#link\""));
    assertTrue(body.contains("\"uri\":\"https://example.com/page\""));
  }

  @Test
  void createRecordRequestBodyOmitsFacetsWhenNoUrl() {
    String body = DefaultBlueskyApiClient.createRecordRequestBody(
        "did:plc:abc",
        "hello world",
        OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(!body.contains("\"facets\":"));
  }
}
