package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class HostAdmissionTest {
  @Test void cancelledAndExpiredWaitersDoNotLeakReferencesOrReleaseAnotherOwnersPermit() {
    var admission = new HostAdmission(1);
    var lease = admission.acquire("EXAMPLE.COM", FetchOperation.active());
    var expired = assertThrows(HttpFetchException.class, () -> admission.acquire("example.com",
        FetchOperation.active().withTimeout(Duration.ofMillis(20))));
    assertEquals(HttpFetchException.Code.TOTAL_TIMEOUT, expired.code());
    assertThrows(CancellationException.class, () -> admission.acquire("example.com",
        new FetchOperation(() -> { throw new CancellationException(); }, Long.MAX_VALUE)));
    assertEquals(1, admission.entries()); lease.close(); lease.close(); assertEquals(0, admission.entries());
    try (var next = admission.acquire("example.com", FetchOperation.active())) {
      assertThrows(HttpFetchException.class, () -> admission.acquire("example.com", FetchOperation.active().withTimeout(Duration.ofMillis(20))));
    }
    assertEquals(0, admission.entries());
  }
  @Test void nestedFetchScopePreservesAdmissionAndCloseRestoresIt() {
    var admission = new HostAdmission(1); URI uri = URI.create("https://example.com/page");
    try (var held = admission.acquire(uri.getHost(), FetchOperation.active())) {
      try (var scope = FetchScope.enter(FetchOperation.active(), admission);
          var nested = FetchScope.enter(FetchOperation.active())) {
        assertThrows(HttpFetchException.class, () -> FetchScope.acquireConnection(uri,
            FetchOperation.active().withTimeout(Duration.ofMillis(20))));
      }
      assertDoesNotThrow(() -> { try (var unconstrained = FetchScope.acquireConnection(uri, FetchOperation.active())) { } });
    }
    assertEquals(0, admission.entries());
  }
  @Test void invalidHostConcurrencyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new HostAdmission(0));
    assertThrows(IllegalArgumentException.class, () -> new HostAdmission(4));
  }
}
