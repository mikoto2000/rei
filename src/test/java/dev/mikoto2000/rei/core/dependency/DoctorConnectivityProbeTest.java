package dev.mikoto2000.rei.core.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 環境診断Connectivity利用可否の確認の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DoctorConnectivityProbeTest {
  /** 外部条件の待機で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void httpResponseReportsOnlyStatusWithoutBodyOrCredentials() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/models",
        exchange -> {
          exchange.sendResponseHeaders(401, 0);
          try (var body = exchange.getResponseBody()) {
            body.write("token=BODY-SECRET".getBytes());
          }
        });
    server.start();
    try (var probe = new JavaHttpDependencyProbe()) {
      var result =
          probe.connectivity(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/models",
              Duration.ofSeconds(2),
              () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.RESPONSE, result.reason());
      assertEquals(401, result.httpStatus());
      assertFalse(result.toString().contains("BODY-SECRET"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              probe.connectivity(
                  "http://user:SECRET@127.0.0.1/models", Duration.ofSeconds(1), () -> {}));
    } finally {
      server.stop(0);
    }
  }

  /** 外部条件の待機で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void refusedConnectionAndTimeoutRemainDistinct() throws Exception {
    int unused;
    try (var port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      unused = port.getLocalPort();
    }
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(1000);
            exchange.sendResponseHeaders(200, -1);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    try (var probe = new JavaHttpDependencyProbe()) {
      var refused =
          probe.connectivity("http://127.0.0.1:" + unused, Duration.ofSeconds(2), () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.CONNECTION_FAILED, refused.reason());
      var timed =
          probe.connectivity(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
              Duration.ofMillis(100),
              () -> {});
      assertEquals(JavaHttpDependencyProbe.ConnectivityReason.TIMEOUT, timed.reason());
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }

  /** 外部条件の待機の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationStopsPendingObservation() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(1000);
          } catch (Exception ignored) {
          } finally {
            exchange.close();
          }
        });
    server.start();
    var checks = new java.util.concurrent.atomic.AtomicInteger();
    try (var probe = new JavaHttpDependencyProbe()) {
      assertThrows(
          CancellationException.class,
          () ->
              probe.connectivity(
                  "http://127.0.0.1:" + server.getAddress().getPort() + "/slow",
                  Duration.ofSeconds(2),
                  () -> {
                    if (checks.incrementAndGet() > 2) throw new CancellationException();
                  }));
      assertTrue(checks.get() > 2);
    } finally {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
