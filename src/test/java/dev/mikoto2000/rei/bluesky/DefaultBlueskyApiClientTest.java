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
  void extractTagFacetsReturnsUtf8ByteIndexes() {
    String text = "日本語 #ハッシュタグ test";
    List<DefaultBlueskyApiClient.TagFacet> facets = DefaultBlueskyApiClient.extractTagFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.TagFacet facet = facets.getFirst();
    assertEquals("ハッシュタグ", facet.tag());
    assertEquals("日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(facet.byteStart() + "#ハッシュタグ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  @Test
  void extractTagFacetsRecognizesHashtagAfterMultibyteTextWithoutSpace() {
    String text = "メッセージ送信可能。#MicrosoftTeams";
    List<DefaultBlueskyApiClient.TagFacet> facets = DefaultBlueskyApiClient.extractTagFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.TagFacet facet = facets.getFirst();
    assertEquals("MicrosoftTeams", facet.tag());
  }

  @Test
  void createRecordRequestBodyIncludesTagFacetWhenHashtagExists() {
    String body = DefaultBlueskyApiClient.createRecordRequestBody(
        "did:plc:abc",
        "Check #Rei",
        OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#tag\""));
    assertTrue(body.contains("\"tag\":\"Rei\""));
  }

  @Test
  void extractMentionFacetsReturnsUtf8ByteIndexesWhenHandleCanBeResolved() {
    String text = "日本語 @alice.bsky.social hello";
    List<DefaultBlueskyApiClient.MentionFacet> facets = DefaultBlueskyApiClient.extractMentionFacets(
        text,
        handle -> "did:plc:alice");

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.MentionFacet facet = facets.getFirst();
    assertEquals("alice.bsky.social", facet.handle());
    assertEquals("did:plc:alice", facet.did());
    assertEquals("日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(facet.byteStart() + "@alice.bsky.social".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  @Test
  void createRecordRequestBodyIncludesMentionFacetWhenMentionHandleCanBeResolved() {
    String body = DefaultBlueskyApiClient.createRecordRequestBody(
        "did:plc:abc",
        "Reply to @alice.bsky.social",
        OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC),
        null,
        handle -> "did:plc:alice");

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#mention\""));
    assertTrue(body.contains("\"did\":\"did:plc:alice\""));
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
