package dev.mikoto2000.rei.llm;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * 用途に応じたモデル利用に必要なチャット逐次データタイムアウトInterceptorを扱う。
 *
 * <p>Keep chat requests alive while receiving data, but bound network read inactivity.
 */
final class ChatStreamTimeoutInterceptor implements Interceptor {
  /** 読み取りタイムアウトのミリ秒数。 */
  static final int READ_TIMEOUT_MILLIS = 120_000;

  /** 用途に応じたモデル利用で用いるinterceptを返す。 */
  @Override
  public Response intercept(Chain chain) throws IOException {
    if (!chain.request().url().encodedPath().endsWith("/chat/completions")) {
      return chain.proceed(chain.request());
    }
    // Spring AI supplies a per-request timeout that overrides the client defaults.
    // Clear the active call deadline after that override, retaining connect/write limits.
    var timeout = chain.call().timeout();
    // OkHttp enters its AsyncTimeout before application interceptors run.
    // Remove the already scheduled watchdog as well as clearing its duration.
    if (timeout instanceof okio.AsyncTimeout asyncTimeout) {
      asyncTimeout.exit();
    }
    timeout.clearTimeout().clearDeadline();
    return chain
        .withReadTimeout(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .proceed(chain.request());
  }
}
