package dev.mikoto2000.rei.llm.capture;

import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * モデル応答の記録に必要な取得記録Interceptorを扱う。
 *
 * <p>Runs after known rewrites. Captures repeatable, known-size bodies with a hard bounded sink.
 */
public final class CaptureInterceptor implements Interceptor {
  /** 保存先。 */
  private final CaptureStore store;

  /** 関連するモデル呼び出しの記録。 */
  private final CaptureStore.LogicalCall logical;

  /** 指定された値や利用先を使って、CaptureInterceptorを初期化する。 */
  public CaptureInterceptor(CaptureStore store, CaptureStore.LogicalCall logical) {
    this.store = store;
    this.logical = logical;
  }

  /** モデル応答の記録で用いるinterceptを返す。 */
  @Override
  public Response intercept(Chain chain) throws IOException {
    var request = chain.request();
    if (logical == null
        || !store.enabled(logical)
        || !request.url().encodedPath().endsWith("/chat/completions"))
      return chain.proceed(request);
    boolean copying = store.tryCopy();
    CaptureStore.AttemptInfo attempt = null;
    try {
      var body = request.body();
      byte[] bytes = null;
      String state = null;
      if (!copying) state = "CAPTURE_FAILED";
      else if (!store.canCopy(logical)) state = "SKIPPED_TOO_LARGE";
      else if (body == null || body.isDuplex()) state = "SKIPPED_UNSUPPORTED";
      else if (body.isOneShot()) state = "SKIPPED_ONE_SHOT";
      else if (body.contentLength() < 0) state = "SKIPPED_UNSUPPORTED";
      else if (body.contentLength() > CaptureStore.BODY_LIMIT) state = "SKIPPED_TOO_LARGE";
      else {
        var bounded = new BoundedBodySink();
        try {
          body.writeTo(bounded.sink());
          bytes = bounded.bytes();
        } catch (BoundedBodySink.TooLarge tooLarge) {
          bounded.clear();
          state = "SKIPPED_TOO_LARGE";
        } catch (IOException | RuntimeException failure) {
          bounded.clear();
          state = "CAPTURE_FAILED";
        }
      }
      attempt =
          store.prepare(
              logical,
              bytes,
              body == null || body.contentType() == null ? null : body.contentType().toString(),
              state);
    } catch (RuntimeException | IOException ignored) {
      try {
        attempt = store.prepare(logical, null, null, "CAPTURE_FAILED");
      } catch (RuntimeException ignoredAgain) {
      }
    } finally {
      if (copying) store.releaseCopy();
    }
    var id = attempt == null ? null : attempt.attemptId();
    update(id, "SEND_STARTED", null);
    try {
      var response = chain.proceed(request);
      update(id, "HTTP_RESPONSE", response.code());
      return response;
    } catch (IOException | RuntimeException error) {
      update(id, chain.call().isCanceled() ? "CANCELLED" : "SEND_FAILED", null);
      throw error;
    }
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private void update(String id, String state, Integer status) {
    try {
      store.outcome(id, state, status);
    } catch (RuntimeException ignored) {
    }
  }
}
