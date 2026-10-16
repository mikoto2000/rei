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

/** A bounded, redirect-free transport, isolated from Spring AI request capture. */
public interface PlanTransport {
  record Response(
      int status, InputStream body, String requestId, String contentType, String contentEncoding)
      implements AutoCloseable {
    public Response(int status, InputStream body, String requestId, String contentType) {
      this(status, body, requestId, contentType, "identity");
    }

    public void close() {
      try {
        body.close();
      } catch (IOException ignored) {
      }
    }

    @Override
    public String toString() {
      return "Response[status=" + status + "]";
    }
  }

  Response send(
      URI uri,
      String method,
      Map<String, String> headers,
      byte[] body,
      Duration timeout,
      BooleanSupplier cancelled);

  final class Http implements PlanTransport {
    private final HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

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
