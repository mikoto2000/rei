package dev.mikoto2000.rei.core.dependency;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * 外部条件の待機に必要なJavaHTTP通信依存条件利用可否の確認を扱う。
 *
 * <p>No redirects, credentials or response-body exposure; one bounded request per probe.
 */
@Component
public class JavaHttpDependencyProbe implements DependencyHttpProbe, AutoCloseable {
  /** 判定条件一覧有効状態。 */
  private boolean predicatesEnabled;

  /** 外部条件の待機で使用する判定条件一覧有効状態を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setPredicatesEnabled(
      @org.springframework.beans.factory.annotation.Value("${rei.predicates.enabled:false}")
          boolean enabled) {
    predicatesEnabled = enabled;
  }

  /** クライアント。 */
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 外部条件の待機におけるConnectivity理由の選択肢。 */
  public enum ConnectivityReason {
    /** 応答。 */
    RESPONSE,
    /** タイムアウト。 */
    TIMEOUT,
    /** 接続失敗。 */
    CONNECTION_FAILED,
    /** 利用不可。 */
    UNAVAILABLE
  }

  /**
   * 外部条件の待機で受け渡すConnectivityを表す。
   *
   * @param reason 理由
   * @param httpStatus HTTP通信状態
   */
  public record Connectivity(ConnectivityReason reason, Integer httpStatus) {}

  /**
   * 外部条件の待機で用いるconnectivityを返す。
   *
   * <p>Same managed client/security boundary; no body, credentials or diagnostic exception text.
   */
  public Connectivity connectivity(String url, Duration timeout, Runnable checkActive) {
    new DependencySpec(DependencySpec.Kind.HTTP_STATUS, url, "200");
    if (timeout == null
        || timeout.compareTo(Duration.ofMillis(100)) < 0
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Connectivity timeout must be 100ms to 30s");
    if (URI.create(url).getQuery() != null)
      throw new IllegalArgumentException("Diagnostic URL must not contain a query");
    checkActive.run();
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    var pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (true) {
        checkActive.run();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return new Connectivity(ConnectivityReason.TIMEOUT, null);
        try {
          var response =
              pending.get(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
          checkActive.run();
          return new Connectivity(ConnectivityReason.RESPONSE, response.statusCode());
        } catch (TimeoutException slice) {
          /* Recheck cancellation and the monotonic deadline. */
        }
      }
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("Connectivity observation cancelled");
    } catch (ExecutionException failure) {
      ConnectivityReason reason = ConnectivityReason.UNAVAILABLE;
      Throwable cause = failure;
      for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
        if (cause instanceof HttpTimeoutException) {
          reason = ConnectivityReason.TIMEOUT;
          break;
        }
        if (cause instanceof java.net.ConnectException)
          reason = ConnectivityReason.CONNECTION_FAILED;
      }
      return new Connectivity(reason, null);
    } finally {
      pending.cancel(true);
    }
  }

  /** 外部条件の待機で用いる利用可否の確認を返す。 */
  @Override
  public DependencyObservation probe(String id, String url, int expectedStatus) {
    new DependencySpec(DependencySpec.Kind.HTTP_STATUS, url, Integer.toString(expectedStatus));
    if (Thread.currentThread().isInterrupted())
      throw new CancellationException("HTTP observation cancelled");
    var request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    try {
      var response = pending.get(2, TimeUnit.SECONDS);
      boolean matches = response.statusCode() == expectedStatus;
      return new DependencyObservation(
          id,
          matches ? DependencyState.COMPLETED : DependencyState.WAITING,
          matches ? "http_status_verified" : "http_status_mismatch");
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("HTTP observation cancelled");
    } catch (ExecutionException | TimeoutException error) {
      return new DependencyObservation(id, DependencyState.WAITING, "http_unavailable");
    } finally {
      pending.cancel(true);
    }
  }

  /** 外部条件の待機で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    client.shutdownNow();
  }

  /** 外部条件の待機で用いる利用可否の確認本文を返す。 */
  @Override
  public DependencyObservation probeBody(String id, String url, int expectedStatus, String sha256) {
    var spec =
        new DependencySpec(
            DependencySpec.Kind.HTTP_BODY_SHA256, url, expectedStatus + ":" + sha256);
    if (Thread.currentThread().isInterrupted())
      throw new CancellationException("HTTP observation cancelled");
    var request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending = client.sendAsync(request, response -> new DigestSubscriber());
    try {
      var response = pending.get(2, TimeUnit.SECONDS);
      if (response.statusCode() != expectedStatus)
        return new DependencyObservation(id, DependencyState.WAITING, "http_status_mismatch");
      boolean matches = response.body().equals(spec.expected().substring(4));
      return new DependencyObservation(
          id,
          matches ? DependencyState.COMPLETED : DependencyState.WAITING,
          matches ? "http_body_digest_verified" : "http_body_digest_mismatch");
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("HTTP observation cancelled");
    } catch (ExecutionException error) {
      for (Throwable cause = error; cause != null; cause = cause.getCause())
        if (cause instanceof BodyLimitExceeded)
          return new DependencyObservation(id, DependencyState.BLOCKED, "http_body_too_large");
      return new DependencyObservation(id, DependencyState.WAITING, "http_unavailable");
    } catch (TimeoutException error) {
      return new DependencyObservation(id, DependencyState.WAITING, "http_unavailable");
    } finally {
      pending.cancel(true);
    }
  }

  /** 外部条件の待機に必要な本文上限上限超過を扱う。 */
  private static final class BodyLimitExceeded extends java.io.IOException {}

  /** 外部条件の待機で用いる利用可否の確認JSON変換を返す。 */
  @Override
  public DependencyObservation probeJson(String id, String url, String expected) {
    new DependencySpec(DependencySpec.Kind.HTTP_JSON_VALUE, url, expected);
    var condition = HttpJsonCondition.parse(expected);
    if (condition.predicate() != null && !predicatesEnabled)
      return new DependencyObservation(id, DependencyState.BLOCKED, "predicate_disabled");
    if (Thread.currentThread().isInterrupted())
      throw new CancellationException("HTTP observation cancelled");
    var request =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2)).GET().build();
    var pending = client.sendAsync(request, response -> new JsonSubscriber());
    try {
      var response = pending.get(2, TimeUnit.SECONDS);
      if (response.statusCode() != condition.status())
        return new DependencyObservation(id, DependencyState.WAITING, "http_status_mismatch");
      if (condition.predicate() != null) {
        var observed = condition.predicate().evaluate(response.body());
        return new DependencyObservation(
            id,
            switch (observed) {
              case SATISFIED -> DependencyState.COMPLETED;
              case UNSATISFIED -> DependencyState.WAITING;
              case UNKNOWN -> DependencyState.BLOCKED;
            },
            switch (observed) {
              case SATISFIED -> "predicate_verified";
              case UNSATISFIED -> "predicate_mismatch";
              case UNKNOWN -> "predicate_unknown";
            });
      }
      boolean matches = condition.matches(response.body());
      return new DependencyObservation(
          id,
          matches ? DependencyState.COMPLETED : DependencyState.WAITING,
          matches ? "http_json_value_verified" : "http_json_value_mismatch");
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("HTTP observation cancelled");
    } catch (ExecutionException error) {
      for (Throwable cause = error; cause != null; cause = cause.getCause())
        if (cause instanceof BodyLimitExceeded)
          return new DependencyObservation(id, DependencyState.BLOCKED, "http_body_too_large");
      return new DependencyObservation(id, DependencyState.WAITING, "http_unavailable");
    } catch (TimeoutException error) {
      return new DependencyObservation(id, DependencyState.WAITING, "http_unavailable");
    } catch (java.io.IOException error) {
      return new DependencyObservation(id, DependencyState.WAITING, "http_json_invalid");
    } finally {
      pending.cancel(true);
    }
  }

  /** 外部条件の待機に必要なJSON変換購読先を扱う。 */
  private static final class JsonSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    /** 委譲先。 */
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();

    /** 購読。 */
    private java.util.concurrent.Flow.Subscription subscription;

    /** 件数。 */
    private long count;

    /** 停止済み。 */
    private boolean stopped;

    /** 外部条件の待機で参照する本文を返す。 */
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    /** 通知に応じて状態や表示を更新するため、購読を受け付ける。 */
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming) {
      subscription = incoming;
      delegate.onSubscribe(incoming);
    }

    /** 通知に応じて状態や表示を更新するため、次を受け付ける。 */
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      if (stopped) return;
      for (var buffer : buffers) count += buffer.remaining();
      if (count > 65536) {
        stopped = true;
        delegate.onError(new BodyLimitExceeded());
        subscription.cancel();
        return;
      }
      delegate.onNext(buffers);
    }

    /** 通知に応じて状態や表示を更新するため、エラーを受け付ける。 */
    public void onError(Throwable error) {
      if (!stopped) {
        stopped = true;
        delegate.onError(error);
      }
    }

    /** 通知に応じて状態や表示を更新するため、完了を受け付ける。 */
    public void onComplete() {
      if (!stopped) {
        stopped = true;
        delegate.onComplete();
      }
    }
  }

  /**
   * 外部条件の待機に必要な内容の照合値購読先を扱う。
   *
   * <p>Hash streaming byte buffers; never retain or decode the response body.
   */
  private static final class DigestSubscriber implements HttpResponse.BodySubscriber<String> {
    /** 結果。 */
    private final CompletableFuture<String> result = new CompletableFuture<>();

    /** 内容の照合値。 */
    private final java.security.MessageDigest digest;

    /** 購読。 */
    private java.util.concurrent.Flow.Subscription subscription;

    /** 件数。 */
    private long count;

    /** 既定の状態でDigestSubscriberを初期化する。 */
    DigestSubscriber() {
      try {
        digest = java.security.MessageDigest.getInstance("SHA-256");
      } catch (java.security.NoSuchAlgorithmException error) {
        throw new IllegalStateException(error);
      }
    }

    /** 外部条件の待機で参照する本文を返す。 */
    public CompletionStage<String> getBody() {
      return result;
    }

    /** 通知に応じて状態や表示を更新するため、購読を受け付ける。 */
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming) {
      if (subscription != null) {
        incoming.cancel();
        return;
      }
      subscription = incoming;
      incoming.request(1);
    }

    /** 通知に応じて状態や表示を更新するため、次を受け付ける。 */
    public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
      if (result.isDone()) return;
      for (var buffer : buffers) {
        count += buffer.remaining();
        if (count > 65536) {
          result.completeExceptionally(new BodyLimitExceeded());
          subscription.cancel();
          return;
        }
        digest.update(buffer.asReadOnlyBuffer());
      }
      subscription.request(1);
    }

    /** 通知に応じて状態や表示を更新するため、エラーを受け付ける。 */
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    /** 通知に応じて状態や表示を更新するため、完了を受け付ける。 */
    public void onComplete() {
      result.complete(java.util.HexFormat.of().formatHex(digest.digest()));
    }
  }
}
