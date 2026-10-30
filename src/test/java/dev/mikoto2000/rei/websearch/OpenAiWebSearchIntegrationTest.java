package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class OpenAiWebSearchIntegrationTest {
  private static final String EMPTY =
      "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[]}}]}";
  private final WebSearchProperties properties = new WebSearchProperties();
  private final List<String> calls = new ArrayList<>();
  private String response = EMPTY;
  private int status = 200;
  private String method;
  private String requestBody;
  private Map<String, String> requestHeaders;
  private HttpFetchPolicy policy;
  private final SafeHttpFetcher fetcher =
      new SafeHttpFetcher() {
        @Override
        public Response fetch(
            URI uri,
            Map<String, String> headers,
            HttpFetchPolicy p,
            FetchOperation op,
            HttpFetchObserver observer) {
          calls.add("brave");
          observer.request(false);
          return new Response(
              200,
              Map.of(),
              "{\"web\":{\"results\":[{\"title\":\"spring\",\"url\":\"https://example.com/a\",\"description\":\"spring\"}]}}"
                  .getBytes(StandardCharsets.UTF_8),
              uri);
        }

        @Override
        public Response fetch(
            URI uri,
            String m,
            byte[] body,
            Map<String, String> headers,
            HttpFetchPolicy p,
            FetchOperation op,
            HttpFetchObserver observer) {
          calls.add("openai");
          method = m;
          requestBody = new String(body, StandardCharsets.UTF_8);
          requestHeaders = headers;
          policy = p;
          observer.request(false);
          observer.status(status);
          return new Response(status, Map.of(), response.getBytes(StandardCharsets.UTF_8), uri);
        }
      };

  private WebSearchProperties.ProviderProperties provider(String name) {
    var p = new WebSearchProperties.ProviderProperties();
    p.setName(name);
    p.setApiKey("test-secret");
    if (name.equals("brave")) p.setBaseUrl("https://search.example.org/search");
    return p;
  }

  private WebSearchService service(WebSearchProperties.ProviderProperties... providers) {
    properties.setEnabled(true);
    properties.setProviders(List.of(providers));
    return new WebSearchService(properties, new JsonMapper(), fetcher);
  }

  @Test
  void postUsesIndependentCredentialsEndpointAndTimeout() throws Exception {
    var p = provider("openai");
    p.setTimeoutSeconds(45);
    assertTrue(service(p).search("spring", 5).isEmpty());
    assertEquals("POST", method);
    assertEquals("Bearer test-secret", requestHeaders.get("authorization"));
    assertEquals("application/json", requestHeaders.get("content-type"));
    assertTrue(requestBody.contains("spring"));
    assertEquals(45, policy.totalTimeout().toSeconds());
    assertEquals(45, policy.readTimeout().toSeconds());
    assertEquals("https://api.openai.com/v1/responses", p.getBaseUrl());
  }

  @Test
  void sufficientEarlierProviderSkipsOpenAi() throws Exception {
    assertEquals(1, service(provider("brave"), provider("openai")).search("spring", 1).size());
    assertEquals(List.of("brave"), calls);
  }

  @Test
  void insufficientEarlierProviderCallsOpenAiAndMerges() throws Exception {
    response =
        EMPTY.replace(
            "\"sources\":[]",
            "\"sources\":[{\"url\":\"https://example.com/a\"},{\"url\":\"https://spring.io/b\",\"title\":\"spring\"}]");
    assertEquals(2, service(provider("brave"), provider("openai")).search("spring", 3).size());
    assertEquals(List.of("brave", "openai"), calls);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 429, 500, 503})
  void httpFailureIsSafeAndPreservesOtherResults(int code) throws Exception {
    status = code;
    var ex =
        assertThrows(
            IllegalStateException.class, () -> service(provider("openai")).search("spring", 2));
    assertTrue(ex.getMessage().contains(Integer.toString(code)));
    assertFalse(ex.toString().contains("test-secret"));
    assertEquals(1, service(provider("brave"), provider("openai")).search("spring", 2).size());
  }

  @Test
  void missingCredentialDoesNotSendRequest() {
    var p = provider("openai");
    p.setApiKey("");
    assertThrows(IllegalStateException.class, () -> service(p).search("spring", 2));
    assertTrue(calls.isEmpty());
  }

  @Test
  void configuredEnvironmentKeyIsIndependentOfChatCredentials() throws Exception {
    properties.setOpenaiApiKey("environment-key");
    var p = provider("openai");
    p.setApiKey("");
    service(p).search("spring", 1);
    assertEquals("Bearer environment-key", requestHeaders.get("authorization"));
  }

  @Test
  void apiUsageIsMeasuredOnceAcrossCacheHits() throws Exception {
    var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    io.micrometer.core.instrument.Metrics.addRegistry(registry);
    response =
        EMPTY.substring(0, EMPTY.length() - 1)
            + ",\"usage\":{\"input_tokens\":11,\"output_tokens\":7,\"total_tokens\":18}}";
    var cacheProperties = new dev.mikoto2000.rei.http.cache.HttpCacheProperties();
    try (var cache =
        new dev.mikoto2000.rei.http.cache.HttpResponseCache(
            cacheProperties,
            java.time.Clock.systemUTC(),
            new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector())) {
      properties.setEnabled(true);
      properties.setProviders(List.of(provider("openai")));
      var service = new WebSearchService(properties, new JsonMapper(), fetcher, cache);
      service.search("spring", 2);
      service.search("spring", 2);
      assertEquals(List.of("openai"), calls);
      assertEquals(
          11,
          registry
              .get("rei.web.search.events")
              .tag("event", "openai_actual_input_tokens")
              .counter()
              .count());
      assertEquals(
          18,
          registry
              .get("rei.web.search.events")
              .tag("event", "openai_actual_total_tokens")
              .counter()
              .count());
      assertEquals(
          1,
          registry
              .get("rei.web.search.events")
              .tag("event", "openai_web_search_calls")
              .counter()
              .count());
    } finally {
      io.micrometer.core.instrument.Metrics.removeRegistry(registry);
      registry.close();
    }
  }

  @Test
  void candidatesAreFetchedAndPageFailuresRemainVisible() throws Exception {
    response =
        EMPTY.replace(
            "\"sources\":[]",
            "\"sources\":[{\"url\":\"https://example.com/a\",\"title\":\"spring\"}]");
    var search = service(provider("openai"));
    var pages = org.mockito.Mockito.mock(dev.mikoto2000.rei.urlfetch.UrlContentFetchService.class);
    org.mockito.Mockito.when(
            pages.fetch(
                org.mockito.ArgumentMatchers.eq("https://example.com/a"),
                org.mockito.ArgumentMatchers.any(HttpFetchPolicy.class)))
        .thenReturn(
            dev.mikoto2000.rei.urlfetch.UrlContentFetchResult.failure(
                "NETWORK_ERROR", "Page unavailable"));
    var pipeline = new WebSearchAndReadService(search, pages, new WebPageExtractor(), properties);
    var result = pipeline.searchAndRead(new WebSearchAndReadRequest("spring", 1, 1));
    org.mockito.Mockito.verify(pages)
        .fetch(
            org.mockito.ArgumentMatchers.eq("https://example.com/a"),
            org.mockito.ArgumentMatchers.any(HttpFetchPolicy.class));
    assertEquals("failed", result.results().getFirst().fetchStatus());
    assertFalse(String.valueOf(result.results().getFirst().content()).contains("generated answer"));
  }

  @Test
  void parserFailuresNeverLogResponseOrCredentials() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(WebSearchService.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      response = "{test-secret malformed response";
      var failure =
          assertThrows(
              IllegalStateException.class, () -> service(provider("openai")).search("spring", 1));
      assertFalse(failure.toString().contains("test-secret"));
      assertEquals(null, failure.getCause());
      assertTrue(
          appender.list.stream()
              .noneMatch(event -> event.getFormattedMessage().contains("test-secret")));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void failed200ResponseIsNotReusedAfterRecovery() throws Exception {
    response = "{\"status\":\"failed\",\"output\":[]}";
    try (var cache =
        new dev.mikoto2000.rei.http.cache.HttpResponseCache(
            new dev.mikoto2000.rei.http.cache.HttpCacheProperties(),
            java.time.Clock.systemUTC(),
            new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector())) {
      properties.setEnabled(true);
      properties.setProviders(List.of(provider("openai")));
      var service = new WebSearchService(properties, new JsonMapper(), fetcher, cache);
      assertThrows(IllegalStateException.class, () -> service.search("spring", 2));
      response = EMPTY;
      assertTrue(service.search("spring", 2).isEmpty());
      assertEquals(2, calls.size());
    }
  }

  @Test void invalidCredentialCannotAppearInHeaderValidationFailure() {
    var p = provider("openai");
    p.setApiKey("test-secret\ninvalid");
    var error = assertThrows(RuntimeException.class, () -> service(p).search("spring",1));
    assertFalse(error.toString().contains("test-secret"));
    assertTrue(calls.isEmpty());
  }
}
