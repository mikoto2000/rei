package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 論文HTTP通信判断基準の結果と状態の契約を検証するテスト。 */
class PaperHttpPolicyTest {
  /** 論文の調査と整理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void retriesAndRedirectsDoNotResetWholeDeadline() {
    var p = config();
    p.setTimeout(Duration.ofMillis(40));
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(p) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          protected Response exchange(URI uri, String type, int maximum, PaperOperation operation) {
            calls.incrementAndGet();
            try {
              Thread.sleep(80);
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
            return new Response(302, "/again", "", new byte[0]);
          }
        };
    assertEquals(
        PaperException.Code.PROVIDER_TIMEOUT,
        assertThrows(
                PaperException.class,
                () ->
                    client.get(
                        URI.create("https://example.org"),
                        "application/pdf",
                        100,
                        PaperOperation.local("s")))
            .code());
    assertEquals(1, calls.get());
  }

  /** 論文の調査と整理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void wrappedConnectionFailureRetries() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            if (calls.incrementAndGet() == 1)
              throw new PaperException(
                  PaperException.Code.SEARCH_FAILED,
                  "io",
                  new java.util.concurrent.ExecutionException(new java.io.IOException()));
            return new Response(200, null, "", new byte[] {1});
          }
        };
    assertArrayEquals(
        new byte[] {1},
        client.get(
            URI.create("https://example.org"), "application/pdf", 100, PaperOperation.local("s")));
    assertEquals(2, calls.get());
  }

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  PaperProperties config() {
    var p = new PaperProperties();
    p.setBackoff(Duration.ZERO);
    return p;
  }

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void retriesRateLimitBoundedly() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(429, null, "0", new byte[0]);
          }
        };
    var e =
        assertThrows(
            PaperException.class,
            () ->
                client.get(
                    URI.create("https://example.org"),
                    "application/pdf",
                    100,
                    PaperOperation.local("s")));
    assertEquals(PaperException.Code.PROVIDER_RATE_LIMITED, e.code());
    assertEquals(3, calls.get());
  }

  /** 論文の調査と整理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void recoversFromServerError() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            return calls.incrementAndGet() == 1
                ? new Response(503, null, "0", new byte[0])
                : new Response(200, null, "", new byte[] {42});
          }
        };
    assertArrayEquals(
        new byte[] {42},
        client.get(
            URI.create("https://example.org"), "application/pdf", 100, PaperOperation.local("s")));
    assertEquals(2, calls.get());
  }

  /** 論文の調査と整理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void redirectTargetIsRevalidated() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(302, "http://127.0.0.1/secret", "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(1, calls.get());
  }

  /** 論文の調査と整理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void redirectLoopsAreBounded() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(302, "/again", "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(6, calls.get());
  }

  /** 論文の調査と整理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notFoundDoesNotRetry() {
    var calls = new AtomicInteger();
    var client =
        new SafePaperHttpClient(config()) {
          /** {@code exchange} の条件を再現するための補助情報を返す。 */
          @Override
          protected Response exchange(URI u, String t, int max, PaperOperation op) {
            calls.incrementAndGet();
            return new Response(404, null, "", new byte[0]);
          }
        };
    assertThrows(
        PaperException.class,
        () ->
            client.get(
                URI.create("https://example.org"),
                "application/pdf",
                100,
                PaperOperation.local("s")));
    assertEquals(1, calls.get());
  }
}
