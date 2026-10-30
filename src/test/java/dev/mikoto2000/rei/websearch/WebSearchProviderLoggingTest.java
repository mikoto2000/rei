package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** 検索先の診断とフォールバックを検証し、機密情報のログ出力を防ぐ。 */
class WebSearchProviderLoggingTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(WebSearchService.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final SafeHttpFetcher fetcher = mock(SafeHttpFetcher.class);
  private final WebSearchProperties properties = new WebSearchProperties();
  private Level originalLevel;

  @BeforeEach
  void start() {
    originalLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    appender.start();
    logger.addAppender(appender);
    properties.setEnabled(true);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(appender);
    logger.setLevel(originalLevel);
    appender.stop();
  }

  @ParameterizedTest
  @ValueSource(ints = {402, 429, 503})
  void logsHttpFailureEvenWhenFallbackSucceeds(int status) throws Exception {
    properties.setProviders(List.of(provider("brave"), provider("duckduckgo")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(
            response(status, "PRIVATE_BODY"),
            response(
                200,
                """
            <div class="result"><a class="result__a" href="https://example.com/">
            Search result</a></div>
            """));

    assertEquals(1, service().search("PRIVATE_QUERY", 1).size());
    assertEquals(1, diagnostics().size());
    assertEquals(Level.WARN, diagnostics().getFirst().getLevel());
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=brave"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=HTTP_ERROR"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("httpStatus=" + status));
    assertSafeLogs();
  }

  @ParameterizedTest
  @EnumSource(
      value = HttpFetchException.Code.class,
      names = {"NETWORK_ERROR", "CONNECT_TIMEOUT", "READ_TIMEOUT", "URL_NOT_ALLOWED"})
  void logsSafeTransportCodeWithoutNestedCause(HttpFetchException.Code code) {
    properties.setProviders(List.of(provider("duckduckgo")));
    var failure = new HttpFetchException(code, new IllegalStateException("PRIVATE_CAUSE"));
    when(fetcher.fetch(any(), any(), any(), any(), any())).thenThrow(failure);

    assertSame(
        failure,
        assertThrows(HttpFetchException.class, () -> service().search("PRIVATE_QUERY", 1)));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=duckduckgo"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=" + code.name()));
    assertSafeLogs();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 202})
  void logsEmptyResultsWithHttpStatusWithoutClaimingFailure(int status) throws Exception {
    properties.setProviders(List.of(provider("duckduckgo")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(response(status, "<html>PRIVATE_BODY</html>"));

    assertTrue(service().search("PRIVATE_QUERY", 1).isEmpty());
    assertEquals(Level.INFO, diagnostics().getFirst().getLevel());
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=duckduckgo"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("results=0"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("httpStatus=" + status));
    assertSafeLogs();
  }

  @Test
  void fallsBackWhenDuckDuckGoReturnsChallengeInsteadOfResults() throws Exception {
    properties.setProviders(List.of(provider("duckduckgo"), provider("brave")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(
            response(202, "<form id='challenge-form'>PRIVATE_BODY</form>"),
            response(
                200,
                """
                {"web":{"results":[{"title":"Result","url":"https://example.com/"}]}}
                """));

    assertEquals(1, service().search("PRIVATE_QUERY", 1).size());
    assertEquals(1, diagnostics().size());
    assertEquals(Level.WARN, diagnostics().getFirst().getLevel());
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=BOT_CHALLENGE"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("httpStatus=202"));
    assertSafeLogs();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<form id='challenge-form'>PRIVATE_BODY</form>",
        "<form action='//duckduckgo.com/anomaly.js?PRIVATE_TOKEN'>PRIVATE_BODY</form>",
        "<div class='anomaly-modal'>PRIVATE_BODY</div>"
      })
  void reportsChallengeEvenWithSuccessfulHttpStatus(String html) {
    properties.setProviders(List.of(provider("duckduckgo")));
    when(fetcher.fetch(any(), any(), any(), any(), any())).thenReturn(response(200, html));

    var failure =
        assertThrows(WebSearchResponseException.class, () -> service().search("PRIVATE_QUERY", 1));

    assertEquals(200, failure.httpStatus());
    assertEquals("BOT_CHALLENGE", failure.diagnosticCode());
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=BOT_CHALLENGE"));
    assertSafeLogs();
  }

  @Test
  void logsEveryFailedProviderAndPreservesFirstFailure() {
    properties.setProviders(List.of(provider("duckduckgo"), provider("brave")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(response(429, "PRIVATE_BODY"), response(402, "PRIVATE_BODY"));

    var failure =
        assertThrows(WebSearchHttpException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertEquals(429, failure.status());
    assertEquals(2, diagnostics().size());
    assertTrue(diagnostics().get(0).getFormattedMessage().contains("provider=duckduckgo"));
    assertTrue(diagnostics().get(0).getFormattedMessage().contains("httpStatus=429"));
    assertTrue(diagnostics().get(1).getFormattedMessage().contains("provider=brave"));
    assertTrue(diagnostics().get(1).getFormattedMessage().contains("httpStatus=402"));
    assertSafeLogs();
  }

  @Test
  void logsParsingFailureWithoutResponseBody() {
    properties.setProviders(List.of(provider("brave")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(response(200, "PRIVATE_INVALID_JSON"));

    assertThrows(Exception.class, () -> service().search("PRIVATE_QUERY", 1));
    assertEquals(1, diagnostics().size());
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=brave"));
    assertSafeLogs();
  }

  @Test
  void doesNotLogCancellationAsProviderFailure() {
    properties.setProviders(List.of(provider("duckduckgo"), provider("brave")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenThrow(new CancellationException("PRIVATE_CANCEL"));

    assertThrows(CancellationException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(diagnostics().isEmpty());
    verify(fetcher, times(1)).fetch(any(), any(), any(), any(), any());
  }

  @Test
  void doesNotLogArbitraryProviderName() {
    properties.setProviders(List.of(provider("PRIVATE_PROVIDER\nforged log")));

    assertThrows(IllegalStateException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=unsupported"));
    assertSafeLogs();
  }

  @Test
  void recordsSuccessfulFallbackAndCorrelatesAttemptsWithoutSearchText() throws Exception {
    properties.setProviders(List.of(provider("duckduckgo"), provider("brave")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(
            response(202, "<html>PRIVATE_BODY</html>"),
            response(
                200,
                "{\"web\":{\"results\":[{\"title\":\"PRIVATE_QUERY\",\"url\":\"https://example.com/\"}]}}"));

    assertEquals(1, service().search("PRIVATE_QUERY", 1).size());
    var messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertEquals(5, messages.size());
    String searchId = messages.getFirst().split("searchId=")[1].split(",")[0];
    assertTrue(messages.stream().allMatch(message -> message.contains("searchId=" + searchId)));
    assertTrue(messages.get(0).contains("provider=duckduckgo, attempt=1"));
    assertTrue(messages.get(1).contains("results=0, totalUniqueResults=0, httpStatus=202"));
    assertTrue(messages.get(2).contains("provider=brave, attempt=2"));
    assertTrue(messages.get(3).contains("results=1, totalUniqueResults=1, httpStatus=200"));
    assertTrue(messages.get(3).contains("durationMs="));
    assertTrue(messages.getLast().contains("attemptedProviders=2, skippedProviders=0, results=1"));
    assertSafeLogs();
  }

  @Test
  void recordsProvidersSkippedBecauseResultsAreSufficient() throws Exception {
    properties.setProviders(List.of(provider("brave"), provider("duckduckgo")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenReturn(
            response(
                200,
                "{\"web\":{\"results\":[{\"title\":\"PRIVATE_QUERY\",\"url\":\"https://example.com/\"}]}}"));
    service().search("PRIVATE_QUERY", 1);
    assertTrue(
        appender
            .list
            .getLast()
            .getFormattedMessage()
            .contains(
                "attemptedProviders=1, skippedProviders=1, results=1, reason=SUFFICIENT_RESULTS"));
    verify(fetcher, times(1)).fetch(any(), any(), any(), any(), any());
    assertSafeLogs();
  }

  @Test
  void recordsProvidersSkippedWhenRequestBudgetIsExhausted() throws Exception {
    properties.setMaxSearchApiCalls(1);
    properties.setProviders(List.of(provider("brave"), provider("duckduckgo")));
    when(fetcher.fetch(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invocation
                  .getArgument(4, dev.mikoto2000.rei.http.HttpFetchObserver.class)
                  .request(false);
              return response(402, "PRIVATE_BODY");
            });
    assertThrows(WebSearchHttpException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(
        appender
            .list
            .getLast()
            .getFormattedMessage()
            .contains(
                "attemptedProviders=1, skippedProviders=1, results=0, reason=REQUEST_BUDGET"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("networkRequest=true"));
    assertSafeLogs();
  }

  @Test
  void identifiesOpenAiAndReportsMissingKeyWithoutSendingRequest() {
    var provider = provider("openai");
    provider.setApiKey("");
    properties.setProviders(List.of(provider));
    assertThrows(IllegalStateException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=openai"));
    assertTrue(
        diagnostics().getFirst().getFormattedMessage().contains("code=API_KEY_NOT_CONFIGURED"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("networkRequest=false"));
    org.mockito.Mockito.verifyNoInteractions(fetcher);
    assertSafeLogs();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 429, 500})
  void reportsOpenAiHttpStatusAndDiagnosticCode(int status) {
    properties.setProviders(List.of(provider("openai")));
    when(fetcher.fetch(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(response(status, "PRIVATE_BODY"));
    assertThrows(IllegalStateException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("provider=openai"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=HTTP_" + status));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("httpStatus=" + status));
    assertSafeLogs();
  }

  @Test
  void reportsOpenAiParsingCodeWithoutLoggingResponseBody() {
    properties.setProviders(List.of(provider("openai")));
    when(fetcher.fetch(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(response(200, "PRIVATE_INVALID_JSON"));
    assertThrows(IllegalStateException.class, () -> service().search("PRIVATE_QUERY", 1));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("code=INVALID_JSON"));
    assertTrue(diagnostics().getFirst().getFormattedMessage().contains("httpStatus=200"));
    assertSafeLogs();
  }

  private List<ILoggingEvent> diagnostics() {
    return appender.list.stream()
        .filter(
            event -> {
              String message = event.getFormattedMessage();
              return message.startsWith("Web search provider failed;")
                  || message.startsWith("Web search provider completed;")
                      && message.contains("results=0,");
            })
        .toList();
  }

  private WebSearchService service() {
    return new WebSearchService(properties, new JsonMapper(), fetcher);
  }

  private WebSearchProperties.ProviderProperties provider(String name) {
    var provider = new WebSearchProperties.ProviderProperties();
    provider.setName(name);
    provider.setBaseUrl("https://example.com/PRIVATE_URL");
    provider.setApiKey("PRIVATE_KEY");
    return provider;
  }

  private SafeHttpFetcher.Response response(int status, String body) {
    return new SafeHttpFetcher.Response(
        status,
        Map.of(),
        body.getBytes(StandardCharsets.UTF_8),
        URI.create("https://example.com/PRIVATE_URL"));
  }

  private void assertSafeLogs() {
    assertFalse(appender.list.isEmpty());
    for (var event : appender.list) {
      assertFalse(event.getFormattedMessage().contains("PRIVATE"));
      assertFalse(event.getFormattedMessage().contains("https://"));
      assertNull(event.getThrowableProxy());
    }
  }
}
