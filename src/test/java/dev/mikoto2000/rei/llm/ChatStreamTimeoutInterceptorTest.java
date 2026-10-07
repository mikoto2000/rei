package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

class ChatStreamTimeoutInterceptorTest {
  @Test
  void responseCanOutliveCallTimeoutAndUsesTwoMinuteReadTimeout() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
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
    var client = new OkHttpClient.Builder().callTimeout(Duration.ofMillis(100))
        .readTimeout(Duration.ofMillis(100))
        .addInterceptor(new ChatStreamTimeoutInterceptor())
        .addInterceptor(chain -> {
          assertThat(chain.call().timeout().timeoutNanos()).isZero();
          readTimeout.set(chain.readTimeoutMillis());
          return chain.proceed(chain.request());
        }).build();
    try {
      var request = new Request.Builder().url("http://127.0.0.1:" + server.getAddress().getPort()
          + "/v1/chat/completions").build();
      try (var response = client.newCall(request).execute()) {
        assertThat(response.body().string()).isEqualTo("ab");
      }
      assertThat(readTimeout.get()).isEqualTo(120_000);
    } finally {
      server.stop(0);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }
}
