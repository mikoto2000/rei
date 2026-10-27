package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation and uncertain writes fail safely before exposing results. */
class HttpApiBoundaryTest {
  @Test
  void malformedInputReturnsStructuredError() {
    var service =
        new HttpApiService(
            new SafeHttpFetcher(), new HttpApiProperties(null, null, null, null, null));
    assertEquals(
        "INVALID_INPUT",
        service.read(new HttpApiRequest(null, null, null, null, null, null, null, null)).error());
  }

  @Test
  void encodedDotSegmentsCannotBypassKnownSideEffectResource() {
    var fetcher = mock(SafeHttpFetcher.class);
    var service =
        new HttpApiService(
            fetcher,
            new HttpApiProperties(
                null, null, List.of(URI.create("https://api.example.com/actions")), null, null));
    for (String path : List.of("/actions", "/safe/%2e%2e/actions", "/%2561ctions")) {
      var result =
          service.read(
              new HttpApiRequest(
                  "https://api.example.com" + path, null, null, null, null, null, null, null));
      assertEquals("URL_NOT_ALLOWED", result.error());
    }
    verifyNoInteractions(fetcher);
  }

  @Test
  void dispatchedWriteTimeoutHasUnknownOutcomeAndSingleAttempt() {
    var fetcher = mock(SafeHttpFetcher.class);
    when(fetcher.exchange(any(), anyString(), any(), anyMap(), any(), any(), any(), anyBoolean()))
        .thenAnswer(
            call -> {
              call.<HttpFetchObserver>getArgument(6).request(false);
              throw new HttpFetchException(HttpFetchException.Code.TOTAL_TIMEOUT);
            });
    var service = new HttpApiService(fetcher, new HttpApiProperties(null, null, null, null, null));
    var result =
        service.write(
            new HttpApiRequest(
                "https://api.example.com/tasks", "POST", null, null, null, null, null, null));
    assertTrue(result.timedOut());
    assertTrue(result.outcomeUnknown());
    verify(fetcher, times(1))
        .exchange(any(), anyString(), any(), anyMap(), any(), any(), any(), anyBoolean());
  }
}
