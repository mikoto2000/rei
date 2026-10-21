package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TRANSPORT;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * ChatGPTとの連携に必要な計画通信経路を扱う。
 *
 * <p>A bounded, redirect-free transport, isolated from Spring AI request capture.
 */
public interface PlanTransport {
  /**
   * ChatGPTとの連携で受け渡す応答を表す。
   *
   * @param status 状態
   * @param body 本文
   * @param requestId 要求の識別子
   * @param contentType 内容の種類
   * @param contentEncoding 内容文字コード
   */
  record Response(
      int status, InputStream body, String requestId, String contentType, String contentEncoding)
      implements AutoCloseable {
    /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Response(int status, InputStream body, String requestId, String contentType) {
      this(status, body, requestId, contentType, "identity");
    }

    /** ChatGPTとの連携で利用した資源や登録を解放し、利用を終了する。 */
    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
      }
    }

    /** ChatGPTとの連携の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "Response[status=" + status + "]";
    }
  }

  /** ChatGPTとの連携で用いる送信を返す。 */
  Response send(
      URI uri,
      String method,
      Map<String, String> headers,
      byte[] body,
      Duration timeout,
      BooleanSupplier cancelled);

  /** ChatGPTとの連携に必要なHTTP通信を扱う。 */
  final class Http implements PlanTransport {
    /** クライアント。 */
    private final HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** ChatGPTとの連携で用いる送信を返す。 */
    public Response send(
        URI uri,
        String method,
        Map<String, String> headers,
        byte[] body,
        Duration timeout,
        BooleanSupplier cancelled) {
      var builder = HttpRequest.newBuilder(uri).timeout(timeout);
      headers.forEach(builder::header);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      long end = System.nanoTime() + timeout.toNanos();
      try {
        while (true) {
          if (cancelled.getAsBoolean()) throw ChatGptException.of(CANCELLED);
          if (System.nanoTime() >= end) throw ChatGptException.of(TIMEOUT);
          try {
            var response = future.get(50, TimeUnit.MILLISECONDS);
            return new Response(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("x-request-id").orElse(null),
                response.headers().firstValue("content-type").orElse(""),
                response.headers().firstValue("content-encoding").orElse("identity"));
          } catch (TimeoutException pending) {
          }
        }
      } catch (ChatGptException e) {
        future.cancel(true);
        future.thenAccept(
            r -> {
              try {
                r.body().close();
              } catch (IOException ignored) {
              }
            });
        throw e;
      } catch (InterruptedException e) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        future.cancel(true);
        throw ChatGptException.of(CANCELLED);
      } catch (Exception e) {
        future.cancel(true);
        throw ChatGptException.of(
            e.getCause() instanceof HttpTimeoutException ? TIMEOUT : TRANSPORT);
      }
    }
  }
}
