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
  private static final String EMPTY = "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[]}}]}";
  private final WebSearchProperties properties = new WebSearchProperties();
  private final List<String> calls = new ArrayList<>();
  private String response = EMPTY;
  private int status = 200;
  private String method;
  private String requestBody;
  private Map<String, String> requestHeaders;
  private HttpFetchPolicy policy;
  private final SafeHttpFetcher fetcher = new SafeHttpFetcher() {
    @Override public Response fetch(URI uri, Map<String,String> headers, HttpFetchPolicy p, FetchOperation op, HttpFetchObserver observer) {
      calls.add("brave");
      observer.request(false);
      return new Response(200, Map.of(), "{\"web\":{\"results\":[{\"title\":\"spring\",\"url\":\"https://example.com/a\",\"description\":\"spring\"}]}}".getBytes(StandardCharsets.UTF_8), uri);
    }
    @Override public Response fetch(URI uri, String m, byte[] body, Map<String,String> headers, HttpFetchPolicy p, FetchOperation op, HttpFetchObserver observer) {
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
  @Test void postUsesIndependentCredentialsEndpointAndTimeout() throws Exception {
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
  @Test void sufficientEarlierProviderSkipsOpenAi() throws Exception {
    assertEquals(1, service(provider("brave"), provider("openai")).search("spring", 1).size());
    assertEquals(List.of("brave"), calls);
  }
  @Test void insufficientEarlierProviderCallsOpenAiAndMerges() throws Exception {
    response = EMPTY.replace("\"sources\":[]", "\"sources\":[{\"url\":\"https://example.com/a\"},{\"url\":\"https://spring.io/b\",\"title\":\"spring\"}]");
    assertEquals(2, service(provider("brave"), provider("openai")).search("spring", 3).size());
    assertEquals(List.of("brave", "openai"), calls);
  }
  @ParameterizedTest @ValueSource(ints={401,403,429,500,503}) void httpFailureIsSafeAndPreservesOtherResults(int code) throws Exception {
    status = code;
    var ex = assertThrows(IllegalStateException.class, () -> service(provider("openai")).search("spring", 2));
    assertTrue(ex.getMessage().contains(Integer.toString(code)));
    assertFalse(ex.toString().contains("test-secret"));
    assertEquals(1, service(provider("brave"),provider("openai")).search("spring", 2).size());
  }
  @Test void missingCredentialDoesNotSendRequest() {
    var p = provider("openai");
    p.setApiKey("");
    assertThrows(IllegalStateException.class, () -> service(p).search("spring",2));
    assertTrue(calls.isEmpty());
  }
}
