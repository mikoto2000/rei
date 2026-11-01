package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Paper固有のredirectループでも次のtransportより先にURIを再検証する。 */
class PaperRedirectPolicyTest {
  /** 実ネットワークを使わず、数値loopbackへの2回目の交換を禁止する。 */
  @Test
  void numericLoopbackRedirectNeverReachesASecondExchange() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(new PaperProperties()) {
          @Override
          protected Response exchange(
              URI uri, String type, int maxBytes, PaperOperation operation) {
            if (calls.incrementAndGet() == 1)
              return new Response(302, "http://2130706433/a", null, new byte[0]);
            return new Response(200, null, null, new byte[] {1});
          }
        };
    var failure =
        assertThrows(
            PaperException.class,
            () ->
                client.get(
                    URI.create("https://example.org/start"),
                    "application/pdf",
                    1000,
                    PaperOperation.local("fixture")));
    assertEquals(PaperException.Code.PDF_DOWNLOAD_FAILED, failure.code());
    assertEquals(1, calls.get(), "Refused redirect must never reach transport");
  }
}
