package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

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
}
