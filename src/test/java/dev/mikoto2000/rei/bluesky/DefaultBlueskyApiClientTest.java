package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 既定BlueskyAPIクライアントの結果と状態の契約を検証するテスト。 */
class DefaultBlueskyApiClientTest {

  /** 付随情報を保持することを確認する。 */
  @Test
  void masksBothSessionTokensAndPreservesMetadata() throws Exception {
    String response =
        """
        {"accessJwt":"access.secret.token","refreshJwt":"refresh.secret.token",
         "did":"did:plc:abc","handle":"test.bsky.social","active":true}
        """;

    String masked = new DefaultBlueskyApiClient().maskSessionResponse(response);
    var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(masked);

    assertEquals("[REDACTED]", body.get("accessJwt").asText());
    assertEquals("[REDACTED]", body.get("refreshJwt").asText());
    assertEquals("did:plc:abc", body.get("did").asText());
    assertEquals("test.bsky.social", body.get("handle").asText());
    assertTrue(body.get("active").asBoolean());
    assertTrue(response.contains("access.secret.token"));
  }

  /** 会話エラー応答を保持することを確認する。 */
  @Test
  void preservesSessionErrorResponseWithoutTokens() {
    String response = "{\"error\":\"AuthenticationRequired\",\"message\":\"Invalid credentials\"}";
    assertEquals(response, new DefaultBlueskyApiClient().maskSessionResponse(response));
  }

  /** Blueskyとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void hidesMalformedSessionResponse() {
    var client = new DefaultBlueskyApiClient();
    assertEquals("[REDACTED]", client.maskSessionResponse("{\"accessJwt\":\"secret"));
    assertEquals("[REDACTED]", client.maskSessionResponse("not-json secret"));
    assertEquals("[REDACTED]", client.maskSessionResponse("[]"));
    assertEquals("[REDACTED]", client.maskSessionResponse(""));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractLinkFacetsReturnsUtf8ByteIndexes() {
    String text = "日本語 https://example.com/abc test";
    List<DefaultBlueskyApiClient.LinkFacet> facets =
        DefaultBlueskyApiClient.extractLinkFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.LinkFacet facet = facets.getFirst();
    assertEquals("https://example.com/abc", facet.uri());
    assertEquals(
        "日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(
        facet.byteStart()
            + "https://example.com/abc".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createRecordRequestBodyIncludesFacetsWhenUrlExists() {
    String body =
        DefaultBlueskyApiClient.createRecordRequestBody(
            "did:plc:abc",
            "Check this https://example.com/page.",
            OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#link\""));
    assertTrue(body.contains("\"uri\":\"https://example.com/page\""));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractTagFacetsReturnsUtf8ByteIndexes() {
    String text = "日本語 #ハッシュタグ test";
    List<DefaultBlueskyApiClient.TagFacet> facets = DefaultBlueskyApiClient.extractTagFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.TagFacet facet = facets.getFirst();
    assertEquals("ハッシュタグ", facet.tag());
    assertEquals(
        "日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(
        facet.byteStart() + "#ハッシュタグ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  /** Blueskyとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void extractTagFacetsRecognizesHashtagAfterMultibyteTextWithoutSpace() {
    String text = "メッセージ送信可能。#MicrosoftTeams";
    List<DefaultBlueskyApiClient.TagFacet> facets = DefaultBlueskyApiClient.extractTagFacets(text);

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.TagFacet facet = facets.getFirst();
    assertEquals("MicrosoftTeams", facet.tag());
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createRecordRequestBodyIncludesTagFacetWhenHashtagExists() {
    String body =
        DefaultBlueskyApiClient.createRecordRequestBody(
            "did:plc:abc",
            "Check #Rei",
            OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#tag\""));
    assertTrue(body.contains("\"tag\":\"Rei\""));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void extractMentionFacetsReturnsUtf8ByteIndexesWhenHandleCanBeResolved() {
    String text = "日本語 @alice.bsky.social hello";
    List<DefaultBlueskyApiClient.MentionFacet> facets =
        DefaultBlueskyApiClient.extractMentionFacets(text, handle -> "did:plc:alice");

    assertEquals(1, facets.size());
    DefaultBlueskyApiClient.MentionFacet facet = facets.getFirst();
    assertEquals("alice.bsky.social", facet.handle());
    assertEquals("did:plc:alice", facet.did());
    assertEquals(
        "日本語 ".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, facet.byteStart());
    assertEquals(
        facet.byteStart()
            + "@alice.bsky.social".getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        facet.byteEnd());
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createRecordRequestBodyIncludesMentionFacetWhenMentionHandleCanBeResolved() {
    String body =
        DefaultBlueskyApiClient.createRecordRequestBody(
            "did:plc:abc",
            "Reply to @alice.bsky.social",
            OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC),
            null,
            handle -> "did:plc:alice");

    assertTrue(body.contains("\"facets\":["));
    assertTrue(body.contains("\"$type\":\"app.bsky.richtext.facet#mention\""));
    assertTrue(body.contains("\"did\":\"did:plc:alice\""));
  }

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void createRecordRequestBodyOmitsFacetsWhenNoUrl() {
    String body =
        DefaultBlueskyApiClient.createRecordRequestBody(
            "did:plc:abc",
            "hello world",
            OffsetDateTime.of(2026, 5, 16, 10, 0, 0, 0, ZoneOffset.UTC));

    assertTrue(!body.contains("\"facets\":"));
  }
}
