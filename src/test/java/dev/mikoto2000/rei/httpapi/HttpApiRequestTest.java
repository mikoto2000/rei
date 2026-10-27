package dev.mikoto2000.rei.httpapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Metadata cannot change after a request has crossed the permission boundary. */
class HttpApiRequestTest {
  @Test
  void snapshotsCallerMapsAndExposesOnlyImmutableMetadata() {
    var query = new HashMap<>(Map.of("status", "open"));
    var headers = new HashMap<>(Map.of("Accept", "application/json"));
    var request =
        new HttpApiRequest("https://example.com", null, query, headers, null, null, null, null);
    query.put("status", "closed");
    headers.put("Authorization", "not-accepted");
    assertEquals(Map.of("status", "open"), request.query());
    assertEquals(Map.of("Accept", "application/json"), request.headers());
    assertThrows(UnsupportedOperationException.class, () -> request.query().clear());
    assertThrows(UnsupportedOperationException.class, () -> request.headers().clear());
  }
}
