package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import dev.mikoto2000.rei.search.*;
import dev.mikoto2000.rei.urlfetch.*;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

class WebBoundedToolOutputTest {
  @Test void allFourActualCallbacksAcceptOldArgumentsAndBoundConvertedOutput() throws Exception {
    var search=mock(WebSearchService.class);var read=mock(WebSearchAndReadService.class);var url=mock(UrlContentFetchService.class);var knowledge=mock(SearchKnowledgeService.class);
    var source=new WebSearchResult("Java streams","https://example.com/reference","日本語\"\\".repeat(10000),null);
    var page=new WebSearchPage(source.title(),source.url(),source.snippet(),null,"Java streams\n".repeat(10000));
    when(search.search("Java streams",1)).thenReturn(List.of(source));
    when(read.searchAndRead(any())).thenReturn(new WebSearchAndReadResponse("Java streams",List.of(WebSearchAndReadItem.fromPage(page,"text/html",null))));
    when(url.fetch(source.url())).thenReturn(UrlContentFetchResult.success(page.content(),"text/html",source.url()));
    when(knowledge.search("Java streams",1,1,0.5,null)).thenReturn(new SearchKnowledgeResult("Java streams",List.of(),WebSearchContext.primaryOnly(List.of(page)),null));
    var callbacks=MethodToolCallbackProvider.builder().toolObjects(new WebSearchTools(search,read),new UrlContentFetchTools(url),new SearchTools(knowledge)).build().getToolCallbacks();
    Map<String,String> inputs=Map.of("webSearch","{\"query\":\"Java streams\",\"limit\":1}",
        "webSearchAndRead","{\"request\":{\"query\":\"Java streams\",\"maxResults\":1,\"readTop\":1}}",
        "fetchUrlContent","{\"url\":\"https://example.com/reference\"}",
        "searchKnowledge","{\"query\":\"Java streams\",\"vectorTopK\":1,\"webTopK\":1,\"threshold\":0.5,\"source\":null}");
    assertEquals(4,callbacks.length);
    for(var callback:callbacks) {
      String output=callback.call(inputs.get(callback.getToolDefinition().name()),new org.springframework.ai.chat.model.ToolContext(Map.of("testContext",true)));
      assertTrue(output.length()<=16000,callback.getToolDefinition().name());
      assertTrue(TokenEstimator.conservative().text(output)<=4096,callback.getToolDefinition().name());
      if(!callback.getToolDefinition().name().equals("searchKnowledge"))assertNotNull(new tools.jackson.databind.json.JsonMapper().readTree(output));
      assertFalse(callback.getToolDefinition().inputSchema().contains("ToolContext"));
    }
  }
}
