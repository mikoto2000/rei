package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class UrlContentFetchServiceTest {

  @Test
  void returnsContentOn2xxTextResponse() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn("hello");
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertTrue(result.success());
    assertEquals("hello", result.content());
  }

  @Test
  void returnsHttpErrorOn4xx() throws Exception {
    HttpClient httpClient = Mockito.mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    HttpResponse<String> response = (HttpResponse<String>) Mockito.mock(HttpResponse.class);
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
    HttpResponse<String> response = (HttpResponse<String>) Mockito.mock(HttpResponse.class);
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
    HttpResponse<String> response = (HttpResponse<String>) Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(null);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    UrlContentFetchService service = new UrlContentFetchService(new UrlValidator(), httpClient);

    UrlContentFetchResult result = service.fetch("https://example.com");

    assertFalse(result.success());
    assertEquals("EXTRACTION_ERROR", result.errorType());
  }
}
