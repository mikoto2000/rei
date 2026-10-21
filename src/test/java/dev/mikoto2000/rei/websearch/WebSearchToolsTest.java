package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** Web検索ツール一覧の結果と状態の契約を検証するテスト。 */
class WebSearchToolsTest {

  /** Web上の情報の調査で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void webSearchDelegatesToService() throws Exception {
    WebSearchService service = Mockito.mock(WebSearchService.class);
    WebSearchTools tools = new WebSearchTools(service, Mockito.mock(WebSearchAndReadService.class));
    List<WebSearchResult> expected =
        List.of(new WebSearchResult("Title", "https://example.com", "Snippet", null));
    when(service.search("spring ai", 3)).thenReturn(expected);

    List<WebSearchResult> actual = tools.webSearch("spring ai", 3);

    assertEquals(expected, actual);
    verify(service).search("spring ai", 3);
  }

  /** Web上の情報の調査で、検索条件に応じて、必要な候補と根拠を選ぶことを確認する。 */
  @Test
  void webSearchAndReadDelegatesToCompositeService() throws Exception {
    WebSearchService searchService = Mockito.mock(WebSearchService.class);
    WebSearchAndReadService compositeService = Mockito.mock(WebSearchAndReadService.class);
    WebSearchTools tools = new WebSearchTools(searchService, compositeService);
    WebSearchAndReadRequest request = new WebSearchAndReadRequest("spring ai", 5, 3);
    WebSearchAndReadResponse expected = new WebSearchAndReadResponse("spring ai", List.of());
    when(compositeService.searchAndRead(request)).thenReturn(expected);

    assertEquals(expected, tools.webSearchAndRead(request));
    verify(compositeService).searchAndRead(request);
  }

  /** Web上の情報の調査で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void toolDescriptionExplainsWhenToUseCompositeAndPrimitiveTools() throws Exception {
    Tool composite =
        WebSearchTools.class
            .getDeclaredMethod(
                "webSearchAndRead",
                WebSearchAndReadRequest.class,
                org.springframework.ai.chat.model.ToolContext.class)
            .getAnnotation(Tool.class);
    Tool primitive =
        WebSearchTools.class
            .getDeclaredMethod(
                "webSearch",
                String.class,
                Integer.class,
                Boolean.class,
                org.springframework.ai.chat.model.ToolContext.class)
            .getAnnotation(Tool.class);

    org.junit.jupiter.api.Assertions.assertTrue(composite.description().contains("default tool"));
    org.junit.jupiter.api.Assertions.assertTrue(composite.description().contains("public web"));
    org.junit.jupiter.api.Assertions.assertTrue(composite.description().contains("webSearch"));
    org.junit.jupiter.api.Assertions.assertTrue(
        composite.description().contains("fetchUrlContent"));
    org.junit.jupiter.api.Assertions.assertTrue(composite.description().contains("readTop = 0"));
    org.junit.jupiter.api.Assertions.assertTrue(
        primitive.description().contains("result metadata"));
    org.junit.jupiter.api.Assertions.assertTrue(primitive.description().contains("URLs"));
    org.junit.jupiter.api.Assertions.assertTrue(
        primitive.description().contains("webSearchAndRead"));
  }

  /** Web上の情報の調査で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void methodToolProviderRegistersCompositeToolWithRequestSchema() {
    WebSearchTools tools =
        new WebSearchTools(
            Mockito.mock(WebSearchService.class), Mockito.mock(WebSearchAndReadService.class));
    var callbacks =
        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    var callback =
        java.util.Arrays.stream(callbacks)
            .filter(candidate -> "webSearchAndRead".equals(candidate.getToolDefinition().name()))
            .findFirst()
            .orElseThrow();

    String schema = callback.getToolDefinition().inputSchema();
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("query"));
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("maxResults"));
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("readTop"));
  }
}
