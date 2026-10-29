package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** チャット逐次データタイムアウトInterceptorの結果と状態の契約を検証するテスト。 */
class ChatStreamTimeoutInterceptorTest {
  /** 応答全体の時間と無通信の時間を分け、遅い応答を読み切れることを確認する。 */
  @Test
  void responseCanOutliveCallTimeoutAndUsesFiveMinuteReadTimeout() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(200, 0);
          try (var body = exchange.getResponseBody()) {
            body.write('a');
            body.flush();
            try {
              Thread.sleep(250);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            body.write('b');
          }
        });
    server.start();
    var readTimeout = new AtomicInteger();
    var client =
        new OkHttpClient.Builder()
            .callTimeout(Duration.ofMillis(100))
            .readTimeout(Duration.ofMillis(100))
            .addInterceptor(new ChatStreamTimeoutInterceptor())
            .addInterceptor(
                chain -> {
                  assertThat(chain.call().timeout().timeoutNanos()).isZero();
                  readTimeout.set(chain.readTimeoutMillis());
                  return chain.proceed(chain.request());
                })
            .build();
    try {
      var request =
          new Request.Builder()
              .url("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions")
              .build();
      try (var response = client.newCall(request).execute()) {
        assertThat(response.body().string()).isEqualTo("ab");
      }
      assertThat(readTimeout.get()).isEqualTo(300_000);
    } finally {
      server.stop(0);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  /** 設定した無通信の上限は、初回応答待ちと本文の途中の両方に適用する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void configuredTimeoutStillBoundsSilentConnections(boolean headersSent) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            if (headersSent) {
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().write('a');
              exchange.getResponseBody().flush();
            }
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    var client =
        new OkHttpClient.Builder()
            .addInterceptor(new ChatStreamTimeoutInterceptor(Duration.ofMillis(150)))
            .build();
    try {
      var request =
          new Request.Builder()
              .url("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions")
              .build();
      assertThatThrownBy(
              () -> {
                try (var response = client.newCall(request).execute()) {
                  response.body().string();
                }
              })
          .isInstanceOf(java.net.SocketTimeoutException.class);
    } finally {
      release.countDown();
      server.stop(0);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  @Test
  void otherEndpointsKeepTheirTimeouts() throws Exception {
    var client =
        new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(9))
            .readTimeout(Duration.ofSeconds(7))
            .addInterceptor(new ChatStreamTimeoutInterceptor(Duration.ofMinutes(8)))
            .addInterceptor(
                chain -> {
                  assertThat(chain.readTimeoutMillis()).isEqualTo(7_000);
                  assertThat(chain.call().timeout().timeoutNanos())
                      .isEqualTo(Duration.ofSeconds(9).toNanos());
                  return new okhttp3.Response.Builder()
                      .request(chain.request())
                      .protocol(okhttp3.Protocol.HTTP_1_1)
                      .code(200)
                      .message("OK")
                      .body(okhttp3.ResponseBody.create("ok", null))
                      .build();
                })
            .build();
    try (var response =
        client.newCall(new Request.Builder().url("https://provider.invalid/v1/embeddings").build())
            .execute()) {
      assertThat(response.body().string()).isEqualTo("ok");
    } finally {
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }
}
