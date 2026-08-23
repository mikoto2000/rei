package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

class UrlContentFetchToolsTest {

  @Test
  void delegatesToServiceAndReturnsResult() {
    UrlContentFetchService service = mock(UrlContentFetchService.class);
    UrlContentFetchResult expected = UrlContentFetchResult.success("page body");
    when(service.fetch("https://example.com")).thenReturn(expected);
    UrlContentFetchTools tools = new UrlContentFetchTools(service);

    UrlContentFetchResult actual = tools.fetchUrlContent("https://example.com");

    assertEquals(expected, actual);
    verify(service).fetch("https://example.com");
  }

  @Test
  void descriptionRoutesKnownUrlsWithoutManualSearchChaining() throws Exception {
    Tool tool = UrlContentFetchTools.class.getDeclaredMethod("fetchUrlContent", String.class).getAnnotation(Tool.class);

    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("exact URL"));
    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("webSearchAndRead"));
  }
}
