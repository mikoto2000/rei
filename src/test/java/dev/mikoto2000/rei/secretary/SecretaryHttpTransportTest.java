package dev.mikoto2000.rei.secretary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class SecretaryHttpTransportTest {
  @Test
  void responseLimitCancelsSubscriptionBeforeRetainingOversizeBody() {
    var body = new SecretaryHttp.BoundedBody();
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    body.onSubscribe(
        new Flow.Subscription() {
          public void request(long n) {}

          public void cancel() {
            cancelled.set(true);
          }
        });
    body.onNext(List.of(ByteBuffer.wrap(new byte[1048577])));
    assertTrue(cancelled.get());
    assertThrows(
        java.util.concurrent.CompletionException.class,
        () -> body.getBody().toCompletableFuture().join());
  }

  @Test
  void responseDoesNotExposeMutableJsonOrLogItsBody() throws Exception {
    var json =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"credential\":\"private\"}");
    var response = new SecretaryHttp.Response(200, json, Map.of("Retry-After", List.of("60")));
    ((com.fasterxml.jackson.databind.node.ObjectNode) json).removeAll();
    assertEquals("private", response.body().path("credential").asText());
    ((com.fasterxml.jackson.databind.node.ObjectNode) response.body()).removeAll();
    assertEquals("private", response.body().path("credential").asText());
    assertFalse(response.toString().contains("private"));
    assertEquals("60", response.header("retry-after"));
  }
}
