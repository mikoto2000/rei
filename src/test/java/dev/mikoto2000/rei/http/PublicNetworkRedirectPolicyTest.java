package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** リダイレクト後も実際のtransportに渡す前に公開ネットワーク制約を適用する。 */
class PublicNetworkRedirectPolicyTest {
  /** transportだけを制御し、fetchのリダイレクト検証自体は実装を通す。 */
  @ParameterizedTest
  @ValueSource(strings = {
      "http://2130706433/a", "http://0177.0.0.1/a", "http://0x7f000001/a",
      "http://127.1/a", "http://127.000.0.1/a", "http://127.0.0.1./a",
      "http://LOCALHOST./a", "http://[::1]/a", "http://[::ffff:127.0.0.1]/a",
      "http://169.254.1.1/a", "http://10.0.0.1/a"
  })
  void refusesUnsafeRedirectBeforeASecondExchange(String target) {
    var exchanges = new AtomicInteger();
    var fetcher = new SafeHttpFetcher() {
      @Override
      public Response exchange(URI uri, Map<String, String> headers, HttpFetchPolicy policy,
          FetchOperation operation, HttpFetchObserver observer, boolean redirect) {
        if (exchanges.incrementAndGet() == 1)
          return new Response(302, Map.of("location", target), new byte[0], uri);
        return new Response(200, Map.of(), new byte[] {1}, uri);
      }
    };
    var failure = assertThrows(HttpFetchException.class,
        () -> fetcher.fetch(URI.create("https://example.org/start"), Map.of(),
            HttpFetchPolicy.html(Duration.ofSeconds(2)), FetchOperation.active(), HttpFetchObserver.NONE));
    assertEquals(HttpFetchException.Code.URL_NOT_ALLOWED, failure.code());
    assertEquals(1, exchanges.get(), "Refused redirect must never reach transport");
  }
}
