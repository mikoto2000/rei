package dev.mikoto2000.rei.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.Response;

/**
 * 用途に応じたモデル利用に必要なチャット逐次データタイムアウトInterceptorを扱う。
 *
 * <p>Keep chat requests alive while receiving data, but bound network read inactivity.
 */
final class ChatStreamTimeoutInterceptor implements Interceptor {
  /** 初回応答や推論中の無通信を許容する既定時間。 */
  static final Duration DEFAULT_READ_TIMEOUT = Duration.ofMinutes(5);

  private final int readTimeoutMillis;

  ChatStreamTimeoutInterceptor() {
    this(DEFAULT_READ_TIMEOUT);
  }

  ChatStreamTimeoutInterceptor(Duration readTimeout) {
    this.readTimeoutMillis = validatedReadTimeoutMillis(readTimeout);
  }

  static int validatedReadTimeoutMillis(Duration timeout) {
    if (timeout == null
        || timeout.compareTo(Duration.ofMillis(1)) < 0
        || timeout.compareTo(Duration.ofHours(1)) > 0)
      throw new IllegalArgumentException("rei.llm.read-timeout must be between 1ms and 1h");
    return Math.toIntExact(timeout.toMillis());
  }

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
        .withReadTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .proceed(chain.request());
  }
}
