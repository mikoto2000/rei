package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** HTTP通信依存条件利用可否の確認の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class HttpDependencyProbeTest {
  /** 外部条件の待機で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void realHttpStatusTransitionAndRedirectRefusal() throws Exception {
    var status = new AtomicInteger(202);
    var redirected = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/state",
        exchange -> {
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.createContext(
        "/redirect",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/target");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/target",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try (var probe = new JavaHttpDependencyProbe()) {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      assertEquals(DependencyState.WAITING, probe.probe("id", base + "/state", 200).state());
      status.set(200);
      assertEquals(DependencyState.COMPLETED, probe.probe("id", base + "/state", 200).state());
      assertEquals(DependencyState.WAITING, probe.probe("id", base + "/redirect", 200).state());
      assertEquals(0, redirected.get());
      assertThrows(IllegalArgumentException.class, () -> probe.probe("id", "file:///private", 200));
    } finally {
      server.stop(0);
    }
  }

  /** 外部条件の待機の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void interruptionAbortsObservationWithoutClaimingSuccess() {
    try (var probe = new JavaHttpDependencyProbe()) {
      Thread.currentThread().interrupt();
      try {
        assertThrows(
            java.util.concurrent.CancellationException.class,
            () -> probe.probe("id", "http://127.0.0.1:1", 200));
      } finally {
        Thread.interrupted();
      }
    }
  }
}
