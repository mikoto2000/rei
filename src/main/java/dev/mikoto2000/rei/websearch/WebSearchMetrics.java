package dev.mikoto2000.rei.websearch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Web上の情報の調査に必要なWeb検索計測値を扱う。
 *
 * <p>Low-cardinality observed events only; no query, URL, credential or body labels.
 */
public final class WebSearchMetrics {
  /** トークン数。 */
  private static final dev.mikoto2000.rei.core.contextbudget.TokenEstimator TOKENS =
      dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative();

  /** 観測。 */
  public static final WebSearchMetrics OBSERVED = new WebSearchMetrics(Metrics.globalRegistry);

  /** 登録台帳。 */
  private final MeterRegistry registry;

  /** 指定された値や利用先を使って、WebSearchMetricsを初期化する。 */
  public WebSearchMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  public void add(String event, long amount) {
    if (amount > 0) registry.counter("rei.web.search.events", "event", event).increment(amount);
  }

  /** Web上の情報の調査に関する提供元を処理する。 */
  public void provider(String provider) {
    registry
        .counter(
            "rei.web.search.providers",
            "provider",
            Set.of("duckduckgo", "brave", "openai").contains(provider) ? provider : "other")
        .increment();
  }

  /** Record only bounded API states, never response text or error messages. */
  public void openAiStatus(String status) {
    registry.counter("rei.web.search.openai.responses", "status",
        Set.of("completed", "failed", "incomplete", "in_progress", "queued", "cancelled").contains(status) ? status : "invalid").increment();
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public void status(int status) {
    registry.counter("rei.web.search.http", "status", Integer.toString(status)).increment();
  }

  /** Web上の情報の調査に関する継続時間を処理する。 */
  public void duration(String stage, long nanos) {
    Timer.builder("rei.web.search.duration")
        .tag("stage", stage)
        .publishPercentiles(0.5, 0.95)
        .register(registry)
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  public void text(String text) {
    int length = text == null ? 0 : text.length();
    add("output_characters", length);
    add("estimated_tokens", TOKENS.text(text));
  }

  /** Web上の情報の調査で用いるHTTP通信を返す。 */
  public dev.mikoto2000.rei.http.HttpFetchObserver http(String provider) {
    return new dev.mikoto2000.rei.http.HttpFetchObserver() {
      /** Web上の情報の調査に関する要求を処理する。 */
      public void request(boolean redirect) {
        add("http_requests", 1);
        if (redirect) add("redirects", 1);
        if (provider != null) {
          provider(provider);
          add("search_api_calls", 1);
        }
      }

      /** 対象の現在の状態を、照会用の情報として返す。 */
      public void status(int status) {
        WebSearchMetrics.this.status(status);
      }

      /** Web上の情報の調査に関するバイト数を処理する。 */
      public void bytes(int bytes) {
        add("received_bytes", bytes);
      }

      /** Web上の情報の調査に関するキャンセルを処理する。 */
      public void cancellation() {
        add("cancellations", 1);
      }

      /** 処理を継続できなかった理由を、失敗情報として表す。 */
      public void failure(dev.mikoto2000.rei.http.HttpFetchException.Code code) {
        if (code == dev.mikoto2000.rei.http.HttpFetchException.Code.CONNECT_TIMEOUT
            || code == dev.mikoto2000.rei.http.HttpFetchException.Code.READ_TIMEOUT
            || code == dev.mikoto2000.rei.http.HttpFetchException.Code.TOTAL_TIMEOUT)
          add("timeouts", 1);
      }
    };
  }
}
