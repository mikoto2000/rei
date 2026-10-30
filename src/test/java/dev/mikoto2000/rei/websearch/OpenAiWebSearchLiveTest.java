package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** Paid live smoke test: both the live profile and a dedicated explicit opt-in are required. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "REI_OPENAI_WEB_SEARCH_LIVE", matches = "true")
class OpenAiWebSearchLiveTest {
  @Test
  void searchWithExplicitPaidApiOptIn() throws Exception {
    String key = System.getenv("OPENAI_API_KEY");
    org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank());
    var p = new WebSearchProperties.ProviderProperties();
    p.setName("openai");
    p.setApiKey(key);
    p.setTimeoutSeconds(45);
    var properties = new WebSearchProperties();
    properties.setEnabled(true);
    properties.setResearchTimeoutSeconds(60);
    properties.setProviders(java.util.List.of(p));
    assertNotNull(
        new WebSearchService(properties, new JsonMapper())
            .search("OpenAI Responses API documentation", 2));
  }
}
