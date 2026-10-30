package dev.mikoto2000.rei.http.cache;

import dev.mikoto2000.rei.core.searchcache.BoundedTtlCache;
import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HostAdmission;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.HttpFetchObserver;
import dev.mikoto2000.rei.http.HttpFetchPolicy;
import dev.mikoto2000.rei.http.SafeHttpFetcher;
import dev.mikoto2000.rei.http.TransferBudget;
import dev.mikoto2000.rei.http.TransferScope;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * 取得した情報の再利用に必要なHTTP通信応答キャッシュを扱う。
 *
 * <p>Shared bounded memory cache; each waiter retains its own deadline and cancellation.
 */
@Component
public final class HttpResponseCache implements AutoCloseable {
  /** 取得した情報の再利用における識別範囲の選択肢。 */
  public enum Namespace {
    /** 検索。 */
    SEARCH,
    /** ページ。 */
    PAGE
  }

  /**
   * 取得した情報の再利用で受け渡す要求を表す。
   *
   * @param uri 参照先のURI
   * @param headers HTTPヘッダー
   * @param policy 判断基準
   * @param namespace 識別範囲
   * @param forceRefresh キャッシュの再確認指定
   * @param variant 種類
   */
  public record Request(
      URI uri,
      Map<String, String> headers,
      HttpFetchPolicy policy,
      Namespace namespace,
      boolean forceRefresh,
      String variant,
      String method,
      String body) {
    /** Preserve all existing GET callers. */
    public Request(URI uri, Map<String, String> headers, HttpFetchPolicy policy,
        Namespace namespace, boolean forceRefresh, String variant) {
      this(uri, headers, policy, namespace, forceRefresh, variant, "GET", "");
    }

    /** 取得した情報の再利用を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Request(
        URI uri,
        Map<String, String> headers,
        HttpFetchPolicy policy,
        Namespace namespace,
        boolean forceRefresh) {
      this(uri, headers, policy, namespace, forceRefresh, "");
    }

    /** 受け取った情報からRequestを作成する。 */
    public Request {
      Objects.requireNonNull(uri);
      Objects.requireNonNull(policy);
      Objects.requireNonNull(namespace);
      Objects.requireNonNull(variant);
      method = Objects.requireNonNull(method).toUpperCase(Locale.ROOT);
      body = Objects.requireNonNull(body);
      if (!Set.of("GET", "POST").contains(method) || "GET".equals(method) && !body.isEmpty())
        throw new IllegalArgumentException("Unsupported cache request method/body");
      if ("POST".equals(method)) body = canonicalJson(body);
      var normalized = new TreeMap<String, String>();
      headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
      headers = Map.copyOf(normalized);
    }

    /** 取得した情報の再利用の情報を、表示や診断で確認できる文字列表現にする。 */
    @Override
    public String toString() {
      return "HttpCacheRequest[" + namespace + "]";
    }
  }

  /** Canonicalize JSON objects recursively; array order remains significant. */
  private static String canonicalJson(String body) {
    try {
      var mapper = new tools.jackson.databind.json.JsonMapper();
      var node = mapper.readTree(body);
      if (node == null) throw new IllegalArgumentException("JSON body required");
      return mapper.writeValueAsString(canonicalValue(node));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("Invalid JSON cache body");
    }
  }

  private static Object canonicalValue(tools.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var sorted = new TreeMap<String, Object>();
      node.properties().forEach(entry -> sorted.put(entry.getKey(), canonicalValue(entry.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      var values = new ArrayList<Object>();
      node.forEach(value -> values.add(canonicalValue(value)));
      return values;
    }
    return node;
  }

  private static SafeHttpFetcher.Response transfer(SafeHttpFetcher fetcher, Request request,
      URI uri, Map<String, String> headers, HttpFetchPolicy policy, FetchOperation operation,
      HttpFetchObserver observer) {
    if ("GET".equals(request.method())) return fetcher.fetch(uri, headers, policy, operation, observer);
    return fetcher.fetch(uri, request.method(), request.body().getBytes(StandardCharsets.UTF_8),
        headers, policy, operation, observer);
  }

  /**
   * 取得した情報の再利用で受け渡す保存済みを表す。
   *
   * @param response 応答
   * @param freshUntil 有効なキャッシュ終了日時
   * @param validatedNanos 検証済みナノ秒数
   * @param freshNanos 有効なキャッシュナノ秒数
   */
  private record Cached(
      SafeHttpFetcher.Response response, Instant freshUntil, long validatedNanos, long freshNanos) {
    /** 取得した情報の再利用において、有効なキャッシュに該当するか判定する。 */
    boolean fresh(Clock clock) {
      return clock.instant().isBefore(freshUntil)
          && System.nanoTime() - validatedNanos < freshNanos;
    }
  }

  /**
   * 取得した情報の再利用で受け渡す読み込み済みの内容を表す。
   *
   * @param response 応答
   * @param shareable 共有できるかどうか
   */
  private record Loaded(SafeHttpFetcher.Response response, boolean shareable) {}

  /** 取得した情報の再利用に必要な無効状態を扱う。 */
  private static final class Disabled {
    /** 起動単位の識別子。 */
    static final HttpResponseCache INSTANCE = create();

    /** 指定された内容から新しい対象を作成する。 */
    static HttpResponseCache create() {
      var properties = new HttpCacheProperties();
      properties.setEnabled(false);
      return new HttpResponseCache(properties, Clock.systemUTC(), new SensitiveInfoDetector());
    }
  }

  /** 取得した情報の再利用で用いる無効状態を返す。 */
  public static HttpResponseCache disabled() {
    return Disabled.INSTANCE;
  }

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 秘密を含む。 */
  private final SensitiveInfoDetector sensitive;

  /** 有効状態。 */
  private final boolean enabled;

  /** 検索有効期間／ページ有効期間／最大Flights。 */
  private final int searchTtl, pageTtl, maximumFlights;

  /** 最大バイト数。 */
  private final long maximumBytes;

  /** 項目一覧。 */
  private final BoundedTtlCache<String, Cached> entries;

  /** 実行先。 */
  private final ThreadPoolExecutor executor;

  /** 排他制御。 */
  private final Object mutex = new Object();

  /** 同じ対象への取得を共有する実行記録。 */
  private final Map<String, Flight> flights = new HashMap<>();

  /** 取得処理と通信経路の対応。 */
  private final Map<SafeHttpFetcher, String> transports =
      Collections.synchronizedMap(new WeakHashMap<>());

  /** 進行中の読み込み。 */
  private final Set<LoadTask> loads = ConcurrentHashMap.newKeySet();

  /** 終了済み。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 実行中。 */
  private final AtomicInteger running = new AtomicInteger();

  /** 指定された値や利用先を使って、HttpResponseCacheを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public HttpResponseCache(
      HttpCacheProperties properties, Clock clock, SensitiveInfoDetector sensitive) {
    properties.validate();
    this.clock = clock;
    this.sensitive = sensitive;
    enabled = properties.isEnabled();
    searchTtl = properties.getSearchTtlSeconds();
    pageTtl = properties.getPageTtlSeconds();
    maximumFlights = properties.getMaxInFlight();
    maximumBytes = properties.getMaxBytes();
    entries =
        new BoundedTtlCache<>(
            Duration.ofSeconds(properties.getRetentionSeconds()),
            properties.getMaxEntries(),
            maximumBytes,
            clock,
            value -> weight(value.response()));
    executor =
        !enabled
            ? null
            : new ThreadPoolExecutor(
                properties.getLoadParallelism(),
                properties.getLoadParallelism(),
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(properties.getLoadQueueCapacity()),
                Thread.ofPlatform().daemon().name("http-cache-load-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public SafeHttpFetcher.Response fetch(
      Request request, FetchOperation caller, HttpFetchObserver observer, SafeHttpFetcher fetcher) {
    if (FetchScope.forceRefresh() && !request.forceRefresh())
      request =
          new Request(
              request.uri(),
              request.headers(),
              request.policy(),
              request.namespace(),
              true,
              request.variant(), request.method(), request.body());
    FetchOperation waiter = caller.withTimeout(request.policy().totalTimeout());
    waiter.check();
    request.policy().validate(request.uri());
    if (closed.get()) throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
    if (!enabled || !HttpCacheRules.requestEligible(request, sensitive)) {
      event(request.namespace(), "bypass");
      return transfer(fetcher, request,
          request.uri(),
          HttpCacheRules.conditions(request, null, request.forceRefresh()),
          request.policy(),
          waiter,
          observer);
    }
    String transport = transports.computeIfAbsent(fetcher, ignored -> UUID.randomUUID().toString());
    var transferBudget = TransferScope.current();
    String key = key(request, request.headers(), transport, false);
    Flight flight;
    boolean owner;
    synchronized (mutex) {
      if (closed.get()) throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
      Cached cached = entries.get(key).orElse(null);
      if (!request.forceRefresh() && cached != null && cached.fresh(clock)) {
        TransferScope.decoded(cached.response().body().length);
        event(request.namespace(), "hit");
        return copy(cached.response());
      }
      event(request.namespace(), "miss");
      Map<String, String> headers =
          HttpCacheRules.conditions(
              request, cached == null ? null : cached.response(), request.forceRefresh());
      String flightKey =
          key(request, headers, transport, request.forceRefresh())
              + (transferBudget == null ? "" : "." + transferBudget.identity());
      flight = flights.get(flightKey);
      owner = flight == null;
      if (owner) {
        if (flights.size() >= maximumFlights)
          throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
        flight =
            new Flight(
                flightKey,
                key,
                request,
                headers,
                cached,
                observer,
                fetcher,
                FetchScope.admission(),
                transferBudget);
        for (var earlier : flights.values())
          if (earlier.key.equals(key)) {
            if (earlier.request.forceRefresh() && !request.forceRefresh()) flight.superseded = true;
            else earlier.superseded = true;
          }
        flights.put(flightKey, flight);
        flight.waiters = 1;
        loads.add(flight.task);
        try {
          executor.execute(flight.task);
        } catch (RejectedExecutionException rejected) {
          stop(flight, new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED));
        }
      } else {
        flight.waiters++;
        event(request.namespace(), "join");
      }
    }
    try {
      Loaded loaded;
      try {
        loaded = await(flight.result, waiter);
      } catch (HttpFetchException failure) {
        if (owner || failure.code() != HttpFetchException.Code.REQUEST_BUDGET) throw failure;
        event(request.namespace(), "unshared_retry");
        return transfer(fetcher, request,
            request.uri(),
            HttpCacheRules.conditions(request, null, request.forceRefresh()),
            request.policy(),
            waiter,
            observer);
      }
      if (!owner && !loaded.shareable()) {
        event(request.namespace(), "unshared_retry");
        return transfer(fetcher, request,
            request.uri(),
            HttpCacheRules.conditions(request, null, request.forceRefresh()),
            request.policy(),
            waiter,
            observer);
      }
      return copy(loaded.response());
    } finally {
      synchronized (mutex) {
        if (--flight.waiters == 0 && !flight.result.isDone())
          stop(flight, new CancellationException());
      }
    }
  }

  /** 必要な状態が整ってから先へ進むため、HTTP通信応答キャッシュを待つ。 */
  private static Loaded await(CompletableFuture<Loaded> future, FetchOperation operation) {
    for (; ; ) {
      operation.check();
      try {
        Loaded value =
            future.get(
                Math.max(1, Math.min(100_000_000L, operation.remaining().toNanos())),
                TimeUnit.NANOSECONDS);
        operation.check();
        return value;
      } catch (TimeoutException pending) {
        operation.check();
      } catch (InterruptedException interrupted) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        throw new CancellationException();
      } catch (ExecutionException failure) {
        throw FetchOperation.classify(failure.getCause());
      }
    }
  }

  /** 取得した情報の再利用に必要なFlightを扱う。 */
  private final class Flight {
    /** 識別子／照合キー。 */
    final String id, key;

    /** 要求。 */
    final Request request;

    /** HTTPヘッダー。 */
    final Map<String, String> headers;

    /** 以前の。 */
    final Cached old;

    /** 観測。 */
    final HttpFetchObserver observer;

    /** 情報の取得元。 */
    final SafeHttpFetcher fetcher;

    /** 受付。 */
    final HostAdmission admission;

    /** 引き継ぎ予算。 */
    final TransferBudget transferBudget;

    /** 停止済み。 */
    final AtomicBoolean stopped = new AtomicBoolean();

    /** 結果。 */
    final CompletableFuture<Loaded> result = new CompletableFuture<>();

    /** タスク。 */
    final LoadTask task;

    /** 結果を待っている利用先の数。 */
    int waiters;

    /** 置き換え済みの内容。 */
    boolean superseded;

    /** 指定された値や利用先を使って、Flightを初期化する。 */
    Flight(
        String id,
        String key,
        Request request,
        Map<String, String> headers,
        Cached old,
        HttpFetchObserver observer,
        SafeHttpFetcher fetcher,
        HostAdmission admission,
        TransferBudget transferBudget) {
      this.id = id;
      this.key = key;
      this.request = request;
      this.headers = headers;
      this.old = old;
      this.observer = observer;
      this.fetcher = fetcher;
      this.admission = admission;
      this.transferBudget = transferBudget;
      task = new LoadTask(this);
    }
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  private void load(Flight flight) {
    Request request = flight.request;
    FetchOperation operation =
        new FetchOperation(
                () -> {
                  if (flight.stopped.get()) throw new CancellationException();
                },
                Long.MAX_VALUE)
            .withTimeout(request.policy().totalTimeout());
    Instant started = clock.instant();
    try (var scope = FetchScope.enter(operation, flight.admission, request.forceRefresh());
        var transfer = TransferScope.enter(flight.transferBudget)) {
      event(request.namespace(), "load");
      var response =
          transfer(flight.fetcher, request,
              request.uri(), flight.headers, request.policy(), operation, flight.observer);
      Instant received = clock.instant();
      if (response.status() == 304) {
        event(request.namespace(), "revalidation");
        response =
            HttpCacheRules.validated(
                flight.old == null ? null : flight.old.response(),
                response,
                flight.headers,
                received);
        TransferScope.decoded(response.body().length);
      } else if (response.status() == 200)
        response =
            new SafeHttpFetcher.Response(
                response.status(),
                response.headers(),
                response.body(),
                response.finalUri(),
                received,
                received);
      boolean shareable =
          weight(response) <= maximumBytes
              && HttpCacheRules.shareable(request, response, sensitive);
      operation.check();
      synchronized (mutex) {
        operation.check();
        if (!flight.superseded && response.status() == 200 && shareable && flight.waiters > 0) {
          Duration ttl =
              HttpCacheRules.freshTtl(
                  request,
                  response,
                  request.namespace() == Namespace.SEARCH ? searchTtl : pageTtl,
                  started,
                  received);
          entries.put(
              flight.key,
              new Cached(copy(response), received.plus(ttl), System.nanoTime(), ttl.toNanos()));
        } else if (!flight.superseded) entries.remove(flight.key);
        flight.result.complete(new Loaded(response, shareable));
        flights.remove(flight.id, flight);
      }
    } catch (Throwable failure) {
      synchronized (mutex) {
        if (!flight.superseded) entries.remove(flight.key);
        flight.result.completeExceptionally(failure);
        flights.remove(flight.id, flight);
      }
    }
  }

  /** 実行中の処理を停止する。 */
  private void stop(Flight flight, RuntimeException reason) {
    flight.superseded = true;
    flight.stopped.set(true);
    flights.remove(flight.id, flight);
    flight.result.completeExceptionally(reason);
    flight.task.cancel(true);
  }

  /** 取得した情報の再利用に必要な読み込みタスクを扱う。 */
  private final class LoadTask extends FutureTask<Void> {
    /** 共有している取得処理の状態。 */
    final Flight flight;

    /** 受付から終了までの管理。 */
    final AtomicInteger lifecycle = new AtomicInteger();

    /** 指定された値や利用先を使って、LoadTaskを初期化する。 */
    LoadTask(Flight flight) {
      super(
          () -> {
            load(flight);
            return null;
          });
      this.flight = flight;
    }

    /** 取得した情報の再利用の操作を実行する。 */
    @Override
    public void run() {
      if (!lifecycle.compareAndSet(0, 1)) return;
      running.incrementAndGet();
      try {
        super.run();
      } finally {
        running.decrementAndGet();
        lifecycle.set(2);
        loads.remove(this);
      }
    }

    /** 取得した情報の再利用に関する完了を処理する。 */
    @Override
    protected void done() {
      if (lifecycle.compareAndSet(0, 2)) {
        executor.remove(this);
        loads.remove(this);
      }
    }
  }

  /** 取得した情報の再利用で用いる複製を返す。 */
  private static SafeHttpFetcher.Response copy(SafeHttpFetcher.Response value) {
    return new SafeHttpFetcher.Response(
        value.status(),
        Map.copyOf(value.headers()),
        value.body().clone(),
        value.finalUri(),
        value.retrievedAt(),
        value.validatedAt());
  }

  /** 取得した情報の再利用で用いる重みを返す。 */
  private static long weight(SafeHttpFetcher.Response value) {
    long size = 1152L + value.body().length + value.finalUri().toASCIIString().length() * 8L;
    for (var header : value.headers().entrySet())
      size += 128L + (header.getKey().length() + header.getValue().length()) * 4L;
    return size;
  }

  /** 対象を照合・参照するためのキーを返す。 */
  private static String key(
      Request request, Map<String, String> headers, String transport, boolean force) {
    try {
      var bytes = new ByteArrayOutputStream();
      var output = new DataOutputStream(bytes);
      field(output, request.namespace().name());
      field(output, request.variant());
      field(output, request.method());
      field(output, request.body());
      field(output, request.uri().toASCIIString());
      field(output, transport);
      output.writeBoolean(force);
      var policy = request.policy();
      output.writeInt(policy.maxWireBytes());
      output.writeInt(policy.maxDecodedBytes());
      output.writeLong(policy.connectTimeout().toNanos());
      output.writeLong(policy.readTimeout().toNanos());
      output.writeLong(policy.totalTimeout().toNanos());
      output.writeInt(policy.maxRedirects());
      field(output, Objects.toString(policy.requiredContentType(), ""));
      field(output, Objects.toString(policy.configuredOrigin(), ""));
      output.writeBoolean(policy.allowPrivateOrigin());
      output.writeInt(headers.size());
      for (var header : new TreeMap<>(headers).entrySet()) {
        field(output, header.getKey());
        field(output, header.getValue());
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Unable to create cache key", impossible);
    }
  }

  /** 取得した情報の再利用に関する項目を処理する。 */
  private static void field(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  /** 取得した情報の再利用に関するイベントを処理する。 */
  private static void event(Namespace namespace, String event) {
    io.micrometer.core.instrument.Metrics.counter(
            "rei.web.cache.events",
            "namespace",
            namespace.name().toLowerCase(Locale.ROOT),
            "event",
            event)
        .increment();
  }

  /** 取得した情報の再利用で用いる大きさを返す。 */
  int size() {
    return entries.size();
  }

  /** 取得した情報の再利用で用いるバイト数を返す。 */
  long bytes() {
    return entries.bytes();
  }

  /** 取得した情報の再利用で用いる入力先Flightを返す。 */
  int inFlight() {
    synchronized (mutex) {
      return flights.size();
    }
  }

  /** 取得した情報の再利用で用いるwaitersを返す。 */
  int waiters() {
    synchronized (mutex) {
      return flights.values().stream().mapToInt(value -> value.waiters).sum();
    }
  }

  /** 取得した情報の再利用で用いる活動中Loadsを返す。 */
  int activeLoads() {
    return running.get();
  }

  /** 取得した情報の再利用で用いる待機中Loadsを返す。 */
  int queuedLoads() {
    return executor == null ? 0 : executor.getQueue().size();
  }

  /** 取得した情報の再利用で利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    synchronized (mutex) {
      closed.set(true);
      for (var flight : new ArrayList<>(flights.values()))
        stop(flight, new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED));
      if (executor != null) {
        loads.forEach(task -> task.cancel(true));
        for (var queued : executor.shutdownNow())
          if (queued instanceof Future<?> future) future.cancel(true);
      }
      entries.clear();
      transports.clear();
    }
    if (executor != null)
      try {
        executor.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
      }
  }
}
