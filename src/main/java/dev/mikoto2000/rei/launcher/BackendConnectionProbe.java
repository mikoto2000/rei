package dev.mikoto2000.rei.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 起動環境の準備に必要なバックエンド接続利用可否の確認を扱う。
 *
 * <p>Authenticated identity check. A successful health check is never sufficient.
 */
public final class BackendConnectionProbe {
  /** 起動環境の準備における状態の選択肢。 */
  public enum Status {
    /** 準備完了。 */
    READY,
    /** 起動を開始している状態。 */
    STARTING,
    /** 古い状態。 */
    STALE,
    /** キー未指定。 */
    KEY_MISSING,
    /** 認証失敗。 */
    AUTHENTICATION_FAILED,
    /** 非互換。 */
    INCOMPATIBLE,
    /** 識別情報MISMATCH。 */
    IDENTITY_MISMATCH,
    /** 接続できない状態。 */
    UNREACHABLE
  }

  /**
   * 起動環境の準備で受け渡す結果を表す。
   *
   * @param status 状態
   */
  public record Result(Status status) {}

  /** クライアント。 */
  private final HttpClient client;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /** 指定された値や利用先を使って、BackendConnectionProbeを初期化する。 */
  public BackendConnectionProbe(HttpClient client) {
    if (client.followRedirects() != HttpClient.Redirect.NEVER)
      throw new IllegalArgumentException("Backend probe must never redirect credentials");
    this.client = client;
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public Result check(BackendEndpoint expected, String key, boolean storageOwned)
      throws InterruptedException {
    if (!storageOwned) return new Result(Status.STALE);
    if (key == null || key.isBlank()) return new Result(Status.KEY_MISSING);
    if (expected.apiProtocolVersion() != BackendEndpoint.CURRENT_API_PROTOCOL)
      return new Result(Status.INCOMPATIBLE);
    try {
      var request =
          HttpRequest.newBuilder(expected.uri().resolve("/api/v1/instance"))
              .timeout(Duration.ofSeconds(3))
              .header("Authorization", "Bearer " + key)
              .GET()
              .build();
      var response = client.send(request, info -> new LimitedBody());
      if (response.statusCode() == 401 || response.statusCode() == 403)
        return new Result(Status.AUTHENTICATION_FAILED);
      if (response.statusCode() == 503) return new Result(Status.STARTING);
      if (response.statusCode() != 200 || response.body().length > 16384)
        return new Result(Status.IDENTITY_MISMATCH);
      BackendEndpoint actual;
      try {
        actual = json.readValue(response.body(), BackendEndpoint.class);
      } catch (IOException | IllegalArgumentException invalid) {
        return new Result(Status.IDENTITY_MISMATCH);
      }
      if (actual.apiProtocolVersion() != BackendEndpoint.CURRENT_API_PROTOCOL)
        return new Result(Status.INCOMPATIBLE);
      return new Result(expected.sameInstance(actual) ? Status.READY : Status.IDENTITY_MISMATCH);
    } catch (IOException connection) {
      return new Result(Status.UNREACHABLE);
    } catch (IllegalArgumentException invalid) {
      return new Result(Status.AUTHENTICATION_FAILED);
    }
  }

  /**
   * 起動環境の準備に必要な制限された本文を扱う。
   *
   * <p>Cap response memory even if an occupied port is serving an unrelated, unbounded body.
   */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    /** 結果。 */
    private final java.util.concurrent.CompletableFuture<byte[]> result =
        new java.util.concurrent.CompletableFuture<>();

    /** バイト数。 */
    private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();

    /** 購読。 */
    private java.util.concurrent.Flow.Subscription subscription;

    /** 起動環境の準備で参照する本文を返す。 */
    public java.util.concurrent.CompletionStage<byte[]> getBody() {
      return result;
    }

    /** 通知に応じて状態や表示を更新するため、購読を受け付ける。 */
    public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
      subscription = value;
      value.request(1);
    }

    /** 通知に応じて状態や表示を更新するため、次を受け付ける。 */
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      if (result.isDone()) return;
      for (var buffer : buffers) {
        if (buffer.remaining() > 16384 - bytes.size()) {
          subscription.cancel();
          result.complete(new byte[0]);
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
      subscription.request(1);
    }

    /** 通知に応じて状態や表示を更新するため、エラーを受け付ける。 */
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    /** 通知に応じて状態や表示を更新するため、完了を受け付ける。 */
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
