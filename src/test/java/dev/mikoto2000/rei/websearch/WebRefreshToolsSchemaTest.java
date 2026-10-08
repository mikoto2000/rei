package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;
import dev.mikoto2000.rei.search.*;
import dev.mikoto2000.rei.urlfetch.*;

class WebRefreshToolsSchemaTest {
  @Test void fourExistingToolsKeepContextHiddenAndRefreshOptional() {
    var callbacks = MethodToolCallbackProvider.builder().toolObjects(
        new WebSearchTools(mock(WebSearchService.class), mock(WebSearchAndReadService.class)),
        new UrlContentFetchTools(mock(UrlContentFetchService.class)),
        new SearchTools(mock(SearchKnowledgeService.class))).build().getToolCallbacks();
    assertEquals(4, callbacks.length);
    for (var callback : callbacks) {
      String schema = callback.getToolDefinition().inputSchema();
      assertFalse(schema.contains("ToolContext")); assertFalse(schema.contains("\"context\""));
      var root = new JsonMapper().readTree(schema);
      var input = callback.getToolDefinition().name().equals("webSearchAndRead")
          ? root.path("properties").path("request") : root;
      assertTrue(input.path("properties").has("forceRefresh"), schema);
      for (var required : input.path("required")) assertNotEquals("forceRefresh", required.asText());
    }
  }
}
