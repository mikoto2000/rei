package dev.mikoto2000.rei.http.cache;

import dev.mikoto2000.rei.core.searchcache.BoundedTtlCache;
import dev.mikoto2000.rei.http.*;
import dev.mikoto2000.rei.memory.util.SensitiveInfoDetector;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;

/** Shared bounded memory cache; each waiter retains its own deadline and cancellation. */
@Component
public final class HttpResponseCache implements AutoCloseable {
  public enum Namespace { SEARCH, PAGE }
  public record Request(URI uri, Map<String, String> headers, HttpFetchPolicy policy, Namespace namespace, boolean forceRefresh, String variant) {
    public Request(URI uri, Map<String, String> headers, HttpFetchPolicy policy, Namespace namespace, boolean forceRefresh) {
      this(uri, headers, policy, namespace, forceRefresh, "");
    }
    public Request {
      Objects.requireNonNull(uri); Objects.requireNonNull(policy); Objects.requireNonNull(namespace);
      Objects.requireNonNull(variant);
      var normalized = new TreeMap<String, String>(); headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
      headers = Map.copyOf(normalized);
    }
    @Override public String toString() { return "HttpCacheRequest[" + namespace + "]"; }
  }
  private record Cached(SafeHttpFetcher.Response response, Instant freshUntil, long validatedNanos, long freshNanos) {
    boolean fresh(Clock clock) { return clock.instant().isBefore(freshUntil) && System.nanoTime() - validatedNanos < freshNanos; }
  }
  private record Loaded(SafeHttpFetcher.Response response, boolean shareable) { }
  private static final class Disabled {
    static final HttpResponseCache INSTANCE = create();
    static HttpResponseCache create() { var properties = new HttpCacheProperties(); properties.setEnabled(false);
      return new HttpResponseCache(properties, Clock.systemUTC(), new SensitiveInfoDetector()); }
  }
  public static HttpResponseCache disabled() { return Disabled.INSTANCE; }
  private final Clock clock;
  private final SensitiveInfoDetector sensitive;
  private final boolean enabled;
  private final int searchTtl, pageTtl, maximumFlights;
  private final long maximumBytes;
  private final BoundedTtlCache<String, Cached> entries;
  private final ThreadPoolExecutor executor;
  private final Object mutex = new Object();
  private final Map<String, Flight> flights = new HashMap<>();
  private final Map<SafeHttpFetcher, String> transports = Collections.synchronizedMap(new WeakHashMap<>());
  private final Set<LoadTask> loads = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicInteger running = new AtomicInteger();
  @org.springframework.beans.factory.annotation.Autowired
  public HttpResponseCache(HttpCacheProperties properties, Clock clock, SensitiveInfoDetector sensitive) {
    properties.validate(); this.clock = clock; this.sensitive = sensitive; enabled = properties.isEnabled();
    searchTtl = properties.getSearchTtlSeconds(); pageTtl = properties.getPageTtlSeconds(); maximumFlights = properties.getMaxInFlight();
    maximumBytes = properties.getMaxBytes();
    entries = new BoundedTtlCache<>(Duration.ofSeconds(properties.getRetentionSeconds()), properties.getMaxEntries(), maximumBytes, clock, value -> weight(value.response()));
    executor = !enabled ? null : new ThreadPoolExecutor(properties.getLoadParallelism(), properties.getLoadParallelism(), 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(properties.getLoadQueueCapacity()), Thread.ofPlatform().daemon().name("http-cache-load-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
  }
  public SafeHttpFetcher.Response fetch(Request request, FetchOperation caller, HttpFetchObserver observer, SafeHttpFetcher fetcher) {
    if (FetchScope.forceRefresh() && !request.forceRefresh())
      request = new Request(request.uri(), request.headers(), request.policy(), request.namespace(), true, request.variant());
    FetchOperation waiter = caller.withTimeout(request.policy().totalTimeout()); waiter.check(); request.policy().validate(request.uri());
    if (closed.get()) throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
    if (!enabled || !HttpCacheRules.requestEligible(request, sensitive)) {
      event(request.namespace(), "bypass");
      return fetcher.fetch(request.uri(), HttpCacheRules.conditions(request, null, request.forceRefresh()), request.policy(), waiter, observer);
    }
    String transport = transports.computeIfAbsent(fetcher, ignored -> UUID.randomUUID().toString());
    var transferBudget = TransferScope.current();
    String key = key(request, request.headers(), transport, false);
    Flight flight; boolean owner;
    synchronized (mutex) {
      if (closed.get()) throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
      Cached cached = entries.get(key).orElse(null);
      if (!request.forceRefresh() && cached != null && cached.fresh(clock)) {
        TransferScope.decoded(cached.response().body().length);
        event(request.namespace(), "hit"); return copy(cached.response());
      }
      event(request.namespace(), "miss");
      Map<String, String> headers = HttpCacheRules.conditions(request, cached == null ? null : cached.response(), request.forceRefresh());
      String flightKey = key(request, headers, transport, request.forceRefresh())
          + (transferBudget == null ? "" : "." + transferBudget.identity());
      flight = flights.get(flightKey); owner = flight == null;
      if (owner) {
        if (flights.size() >= maximumFlights) throw new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED);
        flight = new Flight(flightKey, key, request, headers, cached, observer, fetcher, FetchScope.admission(), transferBudget);
        for (var earlier : flights.values()) if (earlier.key.equals(key)) {
          if (earlier.request.forceRefresh() && !request.forceRefresh()) flight.superseded = true;
          else earlier.superseded = true;
        }
        flights.put(flightKey, flight); flight.waiters = 1; loads.add(flight.task);
        try { executor.execute(flight.task); }
        catch (RejectedExecutionException rejected) { stop(flight, new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED)); }
      } else { flight.waiters++; event(request.namespace(), "join"); }
    }
    try {
      Loaded loaded;
      try { loaded = await(flight.result, waiter); }
      catch (HttpFetchException failure) {
        if (owner || failure.code() != HttpFetchException.Code.REQUEST_BUDGET) throw failure;
        event(request.namespace(), "unshared_retry");
        return fetcher.fetch(request.uri(), HttpCacheRules.conditions(request, null, request.forceRefresh()), request.policy(), waiter, observer);
      }
      if (!owner && !loaded.shareable()) {
        event(request.namespace(), "unshared_retry");
        return fetcher.fetch(request.uri(), HttpCacheRules.conditions(request, null, request.forceRefresh()), request.policy(), waiter, observer);
      }
      return copy(loaded.response());
    } finally {
      synchronized (mutex) {
        if (--flight.waiters == 0 && !flight.result.isDone()) stop(flight, new CancellationException());
      }
    }
  }
  private static Loaded await(CompletableFuture<Loaded> future, FetchOperation operation) {
    for (;;) {
      operation.check();
      try { Loaded value = future.get(Math.max(1, Math.min(100_000_000L, operation.remaining().toNanos())), TimeUnit.NANOSECONDS);
        operation.check(); return value;
      } catch (TimeoutException pending) { operation.check(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
      catch (ExecutionException failure) { throw FetchOperation.classify(failure.getCause()); }
    }
  }
  private final class Flight {
    final String id, key; final Request request; final Map<String, String> headers; final Cached old;
    final HttpFetchObserver observer; final SafeHttpFetcher fetcher; final HostAdmission admission;
    final TransferBudget transferBudget;
    final AtomicBoolean stopped = new AtomicBoolean(); final CompletableFuture<Loaded> result = new CompletableFuture<>();
    final LoadTask task; int waiters; boolean superseded;
    Flight(String id, String key, Request request, Map<String, String> headers, Cached old, HttpFetchObserver observer, SafeHttpFetcher fetcher, HostAdmission admission, TransferBudget transferBudget) {
      this.id = id; this.key = key; this.request = request; this.headers = headers; this.old = old;
      this.observer = observer; this.fetcher = fetcher; this.admission = admission; this.transferBudget = transferBudget; task = new LoadTask(this);
    }
  }
  private void load(Flight flight) {
    Request request = flight.request;
    FetchOperation operation = new FetchOperation(() -> { if (flight.stopped.get()) throw new CancellationException(); }, Long.MAX_VALUE)
        .withTimeout(request.policy().totalTimeout());
    Instant started = clock.instant();
    try (var scope = FetchScope.enter(operation, flight.admission, request.forceRefresh());
        var transfer = TransferScope.enter(flight.transferBudget)) {
      event(request.namespace(), "load");
      var response = flight.fetcher.fetch(request.uri(), flight.headers, request.policy(), operation, flight.observer);
      Instant received = clock.instant();
      if (response.status() == 304) {
        event(request.namespace(), "revalidation");
        response = HttpCacheRules.validated(flight.old == null ? null : flight.old.response(), response, flight.headers, received);
        TransferScope.decoded(response.body().length);
      } else if (response.status() == 200) response = new SafeHttpFetcher.Response(response.status(), response.headers(), response.body(), response.finalUri(), received, received);
      boolean shareable = weight(response) <= maximumBytes && HttpCacheRules.shareable(request, response, sensitive);
      operation.check();
      synchronized (mutex) {
        operation.check();
        if (!flight.superseded && response.status() == 200 && shareable && flight.waiters > 0) {
          Duration ttl = HttpCacheRules.freshTtl(request, response, request.namespace() == Namespace.SEARCH ? searchTtl : pageTtl, started, received);
          entries.put(flight.key, new Cached(copy(response), received.plus(ttl), System.nanoTime(), ttl.toNanos()));
        } else if (!flight.superseded) entries.remove(flight.key);
        flight.result.complete(new Loaded(response, shareable)); flights.remove(flight.id, flight);
      }
    } catch (Throwable failure) {
      synchronized (mutex) { if (!flight.superseded) entries.remove(flight.key); flight.result.completeExceptionally(failure); flights.remove(flight.id, flight); }
    }
  }
  private void stop(Flight flight, RuntimeException reason) {
    flight.superseded = true;
    flight.stopped.set(true); flights.remove(flight.id, flight); flight.result.completeExceptionally(reason); flight.task.cancel(true);
  }
  private final class LoadTask extends FutureTask<Void> {
    final Flight flight; final AtomicInteger lifecycle = new AtomicInteger();
    LoadTask(Flight flight) { super(() -> { load(flight); return null; }); this.flight = flight; }
    @Override public void run() {
      if (!lifecycle.compareAndSet(0, 1)) return;
      running.incrementAndGet();
      try { super.run(); } finally { running.decrementAndGet(); lifecycle.set(2); loads.remove(this); }
    }
    @Override protected void done() {
      if (lifecycle.compareAndSet(0, 2)) { executor.remove(this); loads.remove(this); }
    }
  }
  private static SafeHttpFetcher.Response copy(SafeHttpFetcher.Response value) {
    return new SafeHttpFetcher.Response(value.status(), Map.copyOf(value.headers()), value.body().clone(), value.finalUri(), value.retrievedAt(), value.validatedAt());
  }
  private static long weight(SafeHttpFetcher.Response value) {
    long size = 1152L + value.body().length + value.finalUri().toASCIIString().length() * 8L;
    for (var header : value.headers().entrySet()) size += 128L + (header.getKey().length() + header.getValue().length()) * 4L;
    return size;
  }
  private static String key(Request request, Map<String, String> headers, String transport, boolean force) {
    try {
      var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
      field(output, request.namespace().name()); field(output, request.variant()); field(output, request.uri().toASCIIString()); field(output, transport); output.writeBoolean(force);
      var policy = request.policy(); output.writeInt(policy.maxWireBytes()); output.writeInt(policy.maxDecodedBytes());
      output.writeLong(policy.connectTimeout().toNanos()); output.writeLong(policy.readTimeout().toNanos()); output.writeLong(policy.totalTimeout().toNanos());
      output.writeInt(policy.maxRedirects()); field(output, Objects.toString(policy.requiredContentType(), ""));
      field(output, Objects.toString(policy.configuredOrigin(), "")); output.writeBoolean(policy.allowPrivateOrigin());
      output.writeInt(headers.size()); for (var header : new TreeMap<>(headers).entrySet()) { field(output, header.getKey()); field(output, header.getValue()); }
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException("Unable to create cache key", impossible); }
  }
  private static void field(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
  }
  private static void event(Namespace namespace, String event) {
    io.micrometer.core.instrument.Metrics.counter("rei.web.cache.events", "namespace", namespace.name().toLowerCase(Locale.ROOT), "event", event).increment();
  }
  int size() { return entries.size(); }
  long bytes() { return entries.bytes(); }
  int inFlight() { synchronized (mutex) { return flights.size(); } }
  int waiters() { synchronized (mutex) { return flights.values().stream().mapToInt(value -> value.waiters).sum(); } }
  int activeLoads() { return running.get(); }
  int queuedLoads() { return executor == null ? 0 : executor.getQueue().size(); }
  @jakarta.annotation.PreDestroy @Override public void close() {
    synchronized (mutex) {
      closed.set(true);
      for (var flight : new ArrayList<>(flights.values())) stop(flight, new HttpFetchException(HttpFetchException.Code.FETCH_REJECTED));
      if (executor != null) { loads.forEach(task -> task.cancel(true)); for (var queued : executor.shutdownNow()) if (queued instanceof Future<?> future) future.cancel(true); }
      entries.clear(); transports.clear();
    }
    if (executor != null) try { executor.awaitTermination(2, TimeUnit.SECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  }
}
