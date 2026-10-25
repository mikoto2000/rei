package dev.mikoto2000.rei.secretary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Fixed-origin read transport, with bounded bodies, absolute timeout, and no redirects/retries. */
public final class SecretaryHttp implements AutoCloseable {
  public enum Host {
    GITHUB("https://api.github.com"),
    SLACK("https://slack.com/api");
    private final String origin;

    Host(String origin) {
      this.origin = origin;
    }
  }

  public record Response(int status, JsonNode body, Map<String, List<String>> headers) {
    public Response {
      body = body.deepCopy();
      var copy = new java.util.HashMap<String, List<String>>();
      headers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
      headers = Map.copyOf(copy);
    }

    @Override
    public JsonNode body() {
      return body.deepCopy();
    }

    @Override
    public String toString() {
      return "External HTTP response (" + status + ")";
    }

    public String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .flatMap(e -> e.getValue().stream())
          .findFirst()
          .orElse("");
    }
  }

  private static final ObjectMapper JSON =
      new ObjectMapper(
          com.fasterxml.jackson.core.JsonFactory.builder()
              .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  com.fasterxml.jackson.core.StreamReadConstraints.builder()
                      .maxNestingDepth(32)
                      .maxStringLength(262144)
                      .build())
              .build());
  private static final Semaphore NETWORK = new Semaphore(2);
  private HttpClient client;
  private boolean closed;

  private synchronized HttpClient client() {
    if (closed) throw new SecretaryProvider.Failure("CLOSED", 0, false);
    if (client == null)
      client =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(10))
              .followRedirects(HttpClient.Redirect.NEVER)
              .build();
    return client;
  }

  public Response get(
      Host host, String path, Map<String, String> query, Map<String, String> headers) {
    if (!path.startsWith("/")
        || path.contains("//")
        || path.contains("/../")
        || path.contains("/./")
        || path.contains("?")
        || path.contains("#")) throw new IllegalArgumentException("Invalid API path");
    if (!NETWORK.tryAcquire()) throw new SecretaryProvider.Failure("LOCAL_BUSY", 60, false);
    CompletableFuture<HttpResponse<byte[]>> pending = null;
    try {
      var url = new StringBuilder(host.origin).append(path);
      if (!query.isEmpty()) {
        url.append('?');
        for (var entry : query.entrySet()) {
          if (url.charAt(url.length() - 1) != '?') url.append('&');
          url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
      }
      var request =
          HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(20));
      headers.forEach(request::header);
      pending = client().sendAsync(request.GET().build(), ignored -> new BoundedBody());
      var response = pending.get(20, TimeUnit.SECONDS);
      var body =
          response.body().length == 0 ? JSON.createObjectNode() : JSON.readTree(response.body());
      return new Response(response.statusCode(), body, response.headers().map());
    } catch (InterruptedException interrupted) {
      if (pending != null) pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new SecretaryProvider.Failure("CANCELLED", 0, false);
    } catch (Exception unavailable) {
      if (pending != null) pending.cancel(true);
      if (unavailable.getCause() instanceof SecretaryProvider.Failure bounded) throw bounded;
      throw new SecretaryProvider.Failure("NETWORK", 60, false);
    } finally {
      NETWORK.release();
    }
  }

  static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return result.minimalCompletionStage();
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      var active = subscription;
      if (active == null) {
        result.completeExceptionally(new SecretaryProvider.Failure("INVALID_STREAM", 0, false));
        return;
      }
      for (var buffer : buffers) {
        if (buffer.remaining() > 1048576 - bytes.size()) {
          active.cancel();
          result.completeExceptionally(
              new SecretaryProvider.Failure("RESPONSE_TOO_LARGE", 0, false));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      active.request(1);
    }

    @Override
    public void onError(Throwable failure) {
      result.completeExceptionally(new SecretaryProvider.Failure("NETWORK", 60, false));
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (client != null) client.shutdownNow();
  }
}
