package dev.mikoto2000.rei.launcher;

import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Authenticated identity check. A successful health check is never sufficient. */
public final class BackendConnectionProbe {
  public enum Status { READY, STARTING, STALE, KEY_MISSING, AUTHENTICATION_FAILED,
    INCOMPATIBLE, IDENTITY_MISMATCH, UNREACHABLE }
  public record Result(Status status) {}
  private final HttpClient client;
  private final ObjectMapper json = new ObjectMapper();
  public BackendConnectionProbe(HttpClient client) {
    if (client.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("Backend probe must never redirect credentials");
    this.client = client;
  }
  public Result check(BackendEndpoint expected,String key,boolean storageOwned) throws InterruptedException {
    if (!storageOwned) return new Result(Status.STALE);
    if (key == null || key.isBlank()) return new Result(Status.KEY_MISSING);
    if (expected.apiProtocolVersion() != BackendEndpoint.CURRENT_API_PROTOCOL) return new Result(Status.INCOMPATIBLE);
    try {
      var request = HttpRequest.newBuilder(expected.uri().resolve("/api/v1/instance"))
          .timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+key).GET().build();
      var response = client.send(request,info -> new LimitedBody());
      if (response.statusCode() == 401 || response.statusCode() == 403) return new Result(Status.AUTHENTICATION_FAILED);
      if (response.statusCode() == 503) return new Result(Status.STARTING);
      if (response.statusCode() != 200 || response.body().length > 16384) return new Result(Status.IDENTITY_MISMATCH);
      BackendEndpoint actual;
      try { actual = json.readValue(response.body(),BackendEndpoint.class); }
      catch (IOException | IllegalArgumentException invalid) { return new Result(Status.IDENTITY_MISMATCH); }
      if (actual.apiProtocolVersion() != BackendEndpoint.CURRENT_API_PROTOCOL) return new Result(Status.INCOMPATIBLE);
      return new Result(expected.sameInstance(actual) ? Status.READY : Status.IDENTITY_MISMATCH);
    } catch (IOException connection) { return new Result(Status.UNREACHABLE); }
    catch (IllegalArgumentException invalid) { return new Result(Status.AUTHENTICATION_FAILED); }
  }
  /** Cap response memory even if an occupied port is serving an unrelated, unbounded body. */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    private java.util.concurrent.Flow.Subscription subscription;
    public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      if (result.isDone()) return;
      for (var buffer : buffers) {
        if (buffer.remaining() > 16384-bytes.size()) {
          subscription.cancel(); result.complete(new byte[0]); return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk); bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
  }
}
