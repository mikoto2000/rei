package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import dev.mikoto2000.rei.summarize.JsoupWebPageContentExtractor;
import dev.mikoto2000.rei.summarize.UrlContentWebContentFetcher;

class UrlContentFetchServiceTest {

  @Test
  void returnsContentOn2xxTextResponse() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("hello".getBytes(StandardCharsets.UTF_8));
    when(response.headers()).thenReturn(HttpHeaders.of(
        Map.of("Content-Type", List.of("text/plain; charset=utf-8")), (name, value) -> true));
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertTrue(result.success());
    assertEquals("hello", result.content());
    assertEquals("text/plain", result.contentType());
  }

  @Test
  void decodesBodyUsingCharsetFromContentType() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("日本語の本文".getBytes(Charset.forName("Shift_JIS")));
    when(response.headers()).thenReturn(HttpHeaders.of(
        Map.of("Content-Type", List.of("text/html; charset=Shift_JIS")), (name, value) -> true));
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertTrue(result.success());
    assertEquals("日本語の本文", result.content());
    assertEquals("text/html", result.contentType());
  }

  @Test
  void decodesHtmlBodyUsingMetaCharsetWhenContentTypeHasNoCharset() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("""
        <html>
          <head><meta charset="Shift_JIS"></head>
          <body>日本語の本文</body>
        </html>
        """.getBytes(Charset.forName("Shift_JIS")));
    when(response.headers()).thenReturn(HttpHeaders.of(
        Map.of("Content-Type", List.of("text/html")), (name, value) -> true));
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertTrue(result.success());
    assertTrue(result.content().contains("日本語の本文"));
    assertEquals("text/html", result.contentType());
  }

  @ParameterizedTest
  @MethodSource("htmlCharsetCases")
  void decodesHtmlForSummarize(String contentType, String head, String encoding) throws Exception {
    String html = "<html><head>" + head + "</head><body><article>日本語の本文</article></body></html>";
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(html.getBytes(Charset.forName(encoding)));
    when(response.headers()).thenReturn(HttpHeaders.of(
        contentType == null ? Map.of() : Map.of("Content-Type", List.of(contentType)), (name, value) -> true));
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var fetcher = new UrlContentWebContentFetcher(new UrlContentFetchService(new UrlValidator(), httpClient));

    var result = fetcher.fetch(java.net.URI.create("https://example.com"));

    assertTrue(result.success());
    assertEquals(html, result.content());
    assertEquals("日本語の本文", new JsoupWebPageContentExtractor().extract("https://example.com", result.content()));
  }

  private static Stream<Arguments> htmlCharsetCases() {
    return Stream.of(
        Arguments.of("text/html", "<META CHARSET=Shift_JIS>", "Shift_JIS"),
        Arguments.of("text/html", "<meta charset='EUC-JP'>", "EUC-JP"),
        Arguments.of(null, "<meta charset=windows-31j>", "windows-31j"),
        Arguments.of("text/html", "<meta content='text/html; charset=EUC-JP' http-equiv='Content-Type'>", "EUC-JP"),
        Arguments.of("text/html", "<!-- <meta charset=UTF-8> --><meta charset=Shift_JIS>", "Shift_JIS"),
        Arguments.of("text/html", "<script>const example = '<meta charset=UTF-8>';</script><meta charset=EUC-JP>", "EUC-JP"),
        Arguments.of("text/html", "<meta data-charset=UTF-8><meta charset=Shift_JIS>", "Shift_JIS"),
        Arguments.of("text/html", "<meta charset=invalid-encoding><meta charset=EUC-JP>", "EUC-JP"),
        Arguments.of("text/html; charset=invalid-encoding", "<meta charset=Shift_JIS>", "Shift_JIS"),
        Arguments.of("text/html; charset=\"EUC-JP\"", "<meta charset=UTF-8>", "EUC-JP"),
        Arguments.of("text/html", "<meta charset=invalid-encoding>", "UTF-8"),
        Arguments.of("text/html", "", "UTF-8"));
  }

  @Test
  void returnsHttpErrorOn4xx() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(404);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com/not-found");

    assertFalse(result.success());
    assertEquals("HTTP_ERROR", result.errorType());
    assertEquals(404, result.statusCode());
  }

  @Test
  void returnsHttpErrorOn5xx() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(503);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com/error");

    assertFalse(result.success());
    assertEquals("HTTP_ERROR", result.errorType());
    assertEquals(503, result.statusCode());
  }

  @Test
  void returnsNetworkErrorOnIoException() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("timeout"));
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertFalse(result.success());
    assertEquals("NETWORK_ERROR", result.errorType());
  }

  @Test
  void returnsNetworkErrorOnInterruptedException() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException("interrupted"));
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertFalse(result.success());
    assertEquals("NETWORK_ERROR", result.errorType());
  }

  @Test
  void returnsExtractionErrorWhenBodyExtractionFails() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<byte[]> response = (HttpResponse<byte[]>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(null);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertFalse(result.success());
    assertEquals("EXTRACTION_ERROR", result.errorType());
  }
}
