package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

class WebSearchToolsTest {

  @Test
  void webSearchDelegatesToService() throws Exception {
    WebSearchService service = Mockito.mock(WebSearchService.class);
    WebSearchTools tools = new WebSearchTools(service, Mockito.mock(WebSearchAndReadService.class));
    List<WebSearchResult> expected = List.of(new WebSearchResult("Title", "https://example.com", "Snippet", null));
    when(service.search("spring ai", 3)).thenReturn(expected);

    List<WebSearchResult> actual = tools.webSearch("spring ai", 3);

    assertEquals(expected, actual);
    verify(service).search("spring ai", 3);
  }

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

  @Test
  void toolDescriptionExplainsWhenToUseCompositeAndPrimitiveTools() throws Exception {
    Tool tool = WebSearchTools.class.getDeclaredMethod("webSearchAndRead", WebSearchAndReadRequest.class)
        .getAnnotation(Tool.class);

    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("default tool"));
    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("webSearch"));
    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("fetchUrlContent"));
    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("readTop = 0"));
  }

  @Test
  void methodToolProviderRegistersCompositeToolWithRequestSchema() {
    WebSearchTools tools = new WebSearchTools(Mockito.mock(WebSearchService.class),
        Mockito.mock(WebSearchAndReadService.class));
    var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    var callback = java.util.Arrays.stream(callbacks)
        .filter(candidate -> "webSearchAndRead".equals(candidate.getToolDefinition().name()))
        .findFirst()
        .orElseThrow();

    String schema = callback.getToolDefinition().inputSchema();
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("query"));
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("maxResults"));
    org.junit.jupiter.api.Assertions.assertTrue(schema.contains("readTop"));
  }
}
