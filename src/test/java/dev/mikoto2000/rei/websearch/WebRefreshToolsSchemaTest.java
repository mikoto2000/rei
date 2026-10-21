package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.search.SearchTools;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchService;
import dev.mikoto2000.rei.urlfetch.UrlContentFetchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

/** Web再確認ツール一覧データ形式の結果と状態の契約を検証するテスト。 */
class WebRefreshToolsSchemaTest {
  /** 文脈非表示を維持することを確認する。 */
  @Test
  void fourExistingToolsKeepContextHiddenAndRefreshOptional() {
    var callbacks =
        MethodToolCallbackProvider.builder()
            .toolObjects(
                new WebSearchTools(
                    mock(WebSearchService.class), mock(WebSearchAndReadService.class)),
                new UrlContentFetchTools(mock(UrlContentFetchService.class)),
                new SearchTools(mock(SearchKnowledgeService.class)))
            .build()
            .getToolCallbacks();
    assertEquals(4, callbacks.length);
    for (var callback : callbacks) {
      String schema = callback.getToolDefinition().inputSchema();
      assertFalse(schema.contains("ToolContext"));
      assertFalse(schema.contains("\"context\""));
      var root = new JsonMapper().readTree(schema);
      var input =
          callback.getToolDefinition().name().equals("webSearchAndRead")
              ? root.path("properties").path("request")
              : root;
      assertTrue(input.path("properties").has("forceRefresh"), schema);
      for (var required : input.path("required"))
        assertNotEquals("forceRefresh", required.asText());
    }
  }
}
