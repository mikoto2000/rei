package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

/** ホスト受付の結果と状態の契約を検証するテスト。 */
class HostAdmissionTest {
  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledAndExpiredWaitersDoNotLeakReferencesOrReleaseAnotherOwnersPermit() {
    var admission = new HostAdmission(1);
    var lease = admission.acquire("EXAMPLE.COM", FetchOperation.active());
    var expired =
        assertThrows(
            HttpFetchException.class,
            () ->
                admission.acquire(
                    "example.com", FetchOperation.active().withTimeout(Duration.ofMillis(20))));
    assertEquals(HttpFetchException.Code.TOTAL_TIMEOUT, expired.code());
    assertThrows(
        CancellationException.class,
        () ->
            admission.acquire(
                "example.com",
                new FetchOperation(
                    () -> {
                      throw new CancellationException();
                    },
                    Long.MAX_VALUE)));
    assertEquals(1, admission.entries());
    lease.close();
    lease.close();
    assertEquals(0, admission.entries());
    try (var next = admission.acquire("example.com", FetchOperation.active())) {
      assertThrows(
          HttpFetchException.class,
          () ->
              admission.acquire(
                  "example.com", FetchOperation.active().withTimeout(Duration.ofMillis(20))));
    }
    assertEquals(0, admission.entries());
  }

  /** 受付を保持することを確認する。 */
  @Test
  void nestedFetchScopePreservesAdmissionAndCloseRestoresIt() {
    var admission = new HostAdmission(1);
    URI uri = URI.create("https://example.com/page");
    try (var held = admission.acquire(uri.getHost(), FetchOperation.active())) {
      try (var scope = FetchScope.enter(FetchOperation.active(), admission);
          var nested = FetchScope.enter(FetchOperation.active())) {
        assertThrows(
            HttpFetchException.class,
            () ->
                FetchScope.acquireConnection(
                    uri, FetchOperation.active().withTimeout(Duration.ofMillis(20))));
      }
      assertDoesNotThrow(
          () -> {
            try (var unconstrained = FetchScope.acquireConnection(uri, FetchOperation.active())) {}
          });
    }
    assertEquals(0, admission.entries());
  }

  /** 外部情報の取得で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidHostConcurrencyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new HostAdmission(0));
    assertThrows(IllegalArgumentException.class, () -> new HostAdmission(4));
  }
}
