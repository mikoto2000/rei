package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** フィード認証の結果と状態の契約を検証するテスト。 */
class FeedAuthenticationTest {
  /** 検証に使うトークン。 */
  private static final String TOKEN = "a".repeat(43);

  /** 検証に使う参照先のURL。 */
  private static final String URL = "https://feed.example/feed.xml?limit=30";

  /** 実行環境を読み取ることを確認する。 */
  @Test
  void resolvesOnlyExactFeedAndReadsEnvironmentAtFetchTime() {
    AtomicReference<String> value = new AtomicReference<>(TOKEN);
    var authentication =
        new FeedAuthentication(
            properties(URL, "https://feed.example", "REI_FEED_TOKEN"),
            name -> {
              assertEquals("REI_FEED_TOKEN", name);
              return value.get();
            },
            false);
    assertEquals(TOKEN, authentication.bearerToken(URI.create(URL)).orElseThrow());
    assertTrue(authentication.bearerToken(URI.create("https://feed.example/other.xml")).isEmpty());
    assertTrue(authentication.bearerToken(URI.create(URL + "&cursor=other")).isEmpty());
    value.set("b".repeat(43));
    assertEquals(value.get(), authentication.bearerToken(URI.create(URL)).orElseThrow());
  }

  /** フィードの購読と収集で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void missingInvalidOrOversizedSecretFailsClosedWithoutEchoingValue() {
    for (String value :
        List.of(
            "", "short-secret", TOKEN + "\r\nInjected: yes", "a".repeat(129), "bearer " + TOKEN)) {
      var authentication =
          new FeedAuthentication(
              properties(URL, "https://feed.example", "REI_FEED_TOKEN"), name -> value, false);
      var error =
          assertThrows(FeedFetchException.class, () -> authentication.bearerToken(URI.create(URL)));
      assertFalse(error.toString().contains(value.isEmpty() ? "nonexistent" : value));
      assertNull(error.getCause());
      assertTrue(error.getMessage().contains("token-env"));
    }
    var authentication =
        new FeedAuthentication(
            properties(URL, "https://feed.example", "REI_FEED_TOKEN"), name -> null, false);
    assertThrows(FeedFetchException.class, () -> authentication.bearerToken(URI.create(URL)));
  }

  /** フィードの購読と収集で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void rejectsMisconfiguredBindingsWithoutResolvingSecret() {
    for (String origin :
        List.of(
            "https://evil.example",
            "https://feed.example:444",
            "http://feed.example",
            "https://feed.example/path",
            "https://feed.example?x=y",
            "https://u:secret@feed.example")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new FeedAuthentication(
                  properties(URL, origin, "REI_FEED_TOKEN"),
                  name -> {
                    fail("secret must not be resolved during configuration");
                    return null;
                  },
                  false));
    }
    for (String url :
        List.of(
            "http://feed.example/feed.xml",
            "https://u:secret@feed.example/feed.xml",
            "https://feed.example/feed.xml?token=secret",
            "https://feed.example/feed.xml#secret")) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new FeedAuthentication(
                  properties(url, "https://feed.example", "REI_FEED_TOKEN"), name -> TOKEN, false));
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeedAuthentication(
                properties(URL, "https://feed.example", "literal-secret-value"),
                name -> TOKEN,
                false));
  }

  /** フィードの購読と収集で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateOrOversizedConfigurationFailsClosed() {
    var binding = new FeedProperties.Authentication(URL, "https://feed.example", "REI_FEED_TOKEN");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeedAuthentication(
                new FeedProperties(3, List.of(binding, binding)), name -> TOKEN, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeedAuthentication(
                new FeedProperties(3, java.util.Collections.nCopies(101, binding)),
                name -> TOKEN,
                false));
  }

  /** フィードの購読と収集で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void defaultPortIsEquivalentButHttpLoopbackIsNotEnabledInProduction() {
    var authentication =
        new FeedAuthentication(
            properties(URL, "https://feed.example:443", "REI_FEED_TOKEN"), name -> TOKEN, false);
    assertEquals(TOKEN, authentication.bearerToken(URI.create(URL)).orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new FeedAuthentication(
                properties("http://127.0.0.1/feed.xml", "http://127.0.0.1", "REI_FEED_TOKEN"),
                name -> TOKEN,
                false));
  }

  /** フィードの購読と収集で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void doesNotEchoMistakenSecretInEnvironmentReference() {
    String mistakenValue = "A".repeat(43);
    var authentication =
        new FeedAuthentication(
            properties(URL, "https://feed.example", mistakenValue), name -> null, false);
    var error =
        assertThrows(FeedFetchException.class, () -> authentication.bearerToken(URI.create(URL)));
    assertFalse(error.toString().contains(mistakenValue));
  }

  /** 既定値を保持することを確認する。 */
  @Test
  void springBindsReferenceConfigurationAndPreservesDefaults() {
    var source =
        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
            java.util.Map.of(
                "rei.feed.authentication[0].url", URL,
                "rei.feed.authentication[0].allowed-origin", "https://feed.example",
                "rei.feed.authentication[0].token-env", "REI_FEED_TOKEN"));
    var properties =
        new org.springframework.boot.context.properties.bind.Binder(source)
            .bind(
                "rei.feed",
                org.springframework.boot.context.properties.bind.Bindable.of(FeedProperties.class))
            .get();
    assertEquals(3, properties.briefingMaxItems());
    assertEquals("REI_FEED_TOKEN", properties.authentication().getFirst().tokenEnv());
    var authentication = new FeedAuthentication(properties, name -> TOKEN, false);
    assertEquals(TOKEN, authentication.bearerToken(URI.create(URL)).orElseThrow());
  }

  /** {@code properties} の条件を再現するための補助情報を返す。 */
  static FeedProperties properties(String url, String origin, String env) {
    return new FeedProperties(3, List.of(new FeedProperties.Authentication(url, origin, env)));
  }
}
