package dev.mikoto2000.rei.llm.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

/** 取得記録Interceptorの結果と状態の契約を検証するテスト。 */
class CaptureInterceptorTest {
  /** 大きすぎる単一について、拒否済みを書き込むことを確認する。 */
  @Test
  void oversizedSingleWriteIsRejectedBeforeAnyCaptureBuffering() throws Exception {
    var bounded = new BoundedBodySink();
    var huge = new byte[2 * CaptureStore.BODY_LIMIT];
    assertThatThrownBy(() -> bounded.sink().write(huge))
        .isInstanceOf(BoundedBodySink.TooLarge.class);
    assertThat(bounded.bytes()).isEmpty();
  }

  /** モデル応答の記録で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedCaptureAndDeletedRunDoNotInterfereWithProceed() throws Exception {
    var store = new CaptureStore();
    store.reserve("c", "v");
    store.accept("c", "v", "s", "r");
    var logical = store.logicalCall("r", null);
    var writes = new AtomicInteger();
    var body =
        new RequestBody() {
          /** {@code contentType} の条件を再現するための補助情報を返す。 */
          public MediaType contentType() {
            return null;
          }

          /** {@code contentLength} の条件を再現するための補助情報を返す。 */
          public long contentLength() {
            return 1;
          }

          /** 検証用に、指定された内容を保存先へ反映するため、終点を書き込む。 */
          public void writeTo(okio.BufferedSink sink) throws IOException {
            writes.incrementAndGet();
            throw new IOException("private body must not leak");
          }
        };
    var client =
        new OkHttpClient.Builder()
            .addInterceptor(new CaptureInterceptor(store, logical))
            .addInterceptor(
                chain ->
                    new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create("", null))
                        .build())
            .build();
    try {
      var request =
          new Request.Builder().url("http://example.invalid/chat/completions").post(body).build();
      try (var response = client.newCall(request).execute()) {
        assertThat(response.code()).isEqualTo(200);
      }
      assertThat(store.attempts("r").getFirst().captureState()).isEqualTo("CAPTURE_FAILED");
      store.clear();
      try (var response = client.newCall(request).execute()) {
        assertThat(response.code()).isEqualTo(200);
      }
      assertThat(writes.get()).isEqualTo(1);
      assertThat(store.sessions()).isEmpty();
    } finally {
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  /** モデル応答の記録で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void originalMatchesServerAndHttpStateAndOffDoesNotReadBody() throws Exception {
    var store = new CaptureStore();
    store.reserve("c", "v");
    store.accept("c", "v", "s", "r");
    var call = store.logicalCall("r", null);
    var received = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/chat/completions",
        e -> {
          received.set(e.getRequestBody().readAllBytes());
          e.sendResponseHeaders(200, 0);
          e.close();
        });
    server.start();
    var client =
        new OkHttpClient.Builder().addInterceptor(new CaptureInterceptor(store, call)).build();
    try {
      byte[] bytes = "{\"model\":\"test\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
      try (var response =
          client
              .newCall(
                  new Request.Builder()
                      .url(
                          "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions")
                      .post(RequestBody.create(bytes, MediaType.get("application/json")))
                      .build())
              .execute()) {}
      var attempt = store.attempts("r").getFirst();
      assertThat(attempt.sendState()).isEqualTo("HTTP_RESPONSE");
      assertThat(attempt.httpStatus()).isEqualTo(200);
      assertThat(store.body(attempt.attemptId())).isEqualTo(bytes).isEqualTo(received.get());
    } finally {
      server.stop(0);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }

  /** モデル応答の記録で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void oneShotDuplexUnknownAndTooLargeBodiesAreNotConsumed() throws Exception {
    for (String kind : java.util.List.of("one-shot", "duplex", "unknown", "large")) {
      var store = new CaptureStore();
      store.reserve("c", "v");
      store.accept("c", "v", "s", "r");
      var writes = new AtomicInteger();
      var body =
          new RequestBody() {
            /** {@code contentType} の条件を再現するための補助情報を返す。 */
            public MediaType contentType() {
              return null;
            }

            /** 検証用に、モデル応答の記録において、単一Shotかどうかを判定する。 */
            public boolean isOneShot() {
              return kind.equals("one-shot");
            }

            /** 検証用に、モデル応答の記録において、Duplexかどうかを判定する。 */
            public boolean isDuplex() {
              return kind.equals("duplex");
            }

            /** {@code contentLength} の条件を再現するための補助情報を返す。 */
            public long contentLength() {
              return kind.equals("unknown")
                  ? -1
                  : kind.equals("large") ? CaptureStore.BODY_LIMIT + 1 : 1;
            }

            /** 検証用に、指定された内容を保存先へ反映するため、終点を書き込む。 */
            public void writeTo(okio.BufferedSink sink) throws IOException {
              writes.incrementAndGet();
              sink.writeByte(1);
            }
          };
      var client =
          new OkHttpClient.Builder()
              .addInterceptor(new CaptureInterceptor(store, store.logicalCall("r", null)))
              .addInterceptor(
                  chain ->
                      new Response.Builder()
                          .request(chain.request())
                          .protocol(Protocol.HTTP_1_1)
                          .code(200)
                          .message("OK")
                          .body(ResponseBody.create("", null))
                          .build())
              .build();
      try (var response =
          client
              .newCall(
                  new Request.Builder()
                      .url("http://example.invalid/chat/completions")
                      .post(body)
                      .build())
              .execute()) {}
      assertThat(writes.get()).isZero();
      assertThat(store.attempts("r").getFirst().captureState()).startsWith("SKIPPED_");
      assertThatThrownBy(() -> store.body(store.attempts("r").getFirst().attemptId()))
          .isInstanceOf(IllegalArgumentException.class);
      client.connectionPool().evictAll();
      client.dispatcher().executorService().shutdown();
    }
  }
}
