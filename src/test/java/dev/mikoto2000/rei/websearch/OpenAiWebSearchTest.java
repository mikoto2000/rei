package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class OpenAiWebSearchTest {
  private final JsonMapper mapper = new JsonMapper();
  private final WebSearchProperties.ProviderProperties provider = new WebSearchProperties.ProviderProperties();

  @Test void requestRequiresSearchAndSourcesWithoutStorage() {
    provider.setModel("gpt-4.1-mini");
    provider.setAllowedDomains(List.of("example.com"));
    provider.setBlockedDomains(List.of("bad.example.com"));
    var body = mapper.readTree(new OpenAiWebSearch(mapper).request(provider, "test query"));
    assertEquals("gpt-4.1-mini", body.path("model").asString());
    assertEquals("test query", body.path("input").asString());
    assertEquals("required", body.path("tool_choice").asString());
    assertFalse(body.path("store").asBoolean());
    assertEquals("web_search_call.action.sources", body.path("include").get(0).asString());
    assertEquals("web_search", body.path("tools").get(0).path("type").asString());
    assertEquals("medium", body.path("tools").get(0).path("search_context_size").asString());
    assertEquals("example.com", body.path("tools").get(0).path("filters").path("allowed_domains").get(0).asString());
    assertEquals("bad.example.com", body.path("tools").get(0).path("filters").path("blocked_domains").get(0).asString());
  }

  @Test void structuredSourcesAndCitationsAreNormalizedMergedAndFiltered() {
    provider.setAllowedDomains(List.of("example.com"));
    provider.setBlockedDomains(List.of("bad.example.com"));
    var results = new OpenAiWebSearch(mapper).parse(provider, """
        {"status":"completed","output":[
          {"type":"web_search_call","status":"completed","action":{"type":"search","sources":[
            {"type":"url","url":"https://EXAMPLE.com:443/a?utm_source=x#fragment"},
            {"type":"url","url":"https://bad.example.com/a"},
            {"type":"url","url":"https://other.org/a"},
            {"type":"url","url":"http://127.0.0.1/private"}]}},
          {"type":"message","content":[{"type":"output_text","text":"https://invented.example.com/",
            "annotations":[{"type":"url_citation","url":"https://example.com/a","title":"Source title"},
            {"type":"url_citation","url":"https://docs.example.com/b","title":"Second"}]}]}],
          "usage":{"input_tokens":11,"output_tokens":7,"total_tokens":18}}
        """, 5);
    assertEquals(2, results.size());
    assertEquals("https://example.com/a", results.getFirst().url());
    assertEquals("Source title", results.getFirst().title());
    assertEquals("", results.getFirst().snippet());
    assertNull(results.getFirst().publishedAt());
  }

  @Test void completedEmptySearchIsNotFailure() {
    assertTrue(new OpenAiWebSearch(mapper).parse(provider,
        "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"sources\":[]}}]}", 5).isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {
    "not json", "null", "{}", "{\"status\":\"failed\",\"output\":[]}",
    "{\"status\":\"incomplete\",\"output\":[]}", "{\"status\":\"completed\",\"output\":[]}",
    "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"failed\"}]}",
    "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\",\"status\":\"completed\"}]}"
  }) void malformedOrFailedResponsesAreErrors(String json) {
    assertThrows(IllegalStateException.class, () -> new OpenAiWebSearch(mapper).parse(provider, json, 5));
  }
}

