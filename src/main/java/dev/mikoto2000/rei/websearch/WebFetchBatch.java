package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.stereotype.Component;

/** One shared bounded HTTP worker pool; caller deadlines and rank survive executor boundaries. */
@Component
public final class WebFetchBatch implements AutoCloseable {
  public enum Status { SUCCESS, FAILED, TIMEOUT, REJECTED }
  public record Outcome<T>(Status status, T value, String errorType) {
    public boolean success() { return status == Status.SUCCESS; }
  }
  @FunctionalInterface public interface Fetcher<T> { T fetch(WebSearchSelection.Candidate candidate) throws Exception; }
  private static final class Defaults { static final WebFetchBatch INSTANCE = new WebFetchBatch(3, 2, 32); }
  static WebFetchBatch sharedDefaults() { return Defaults.INSTANCE; }
  private final ThreadPoolExecutor executor;
  private final HostAdmission loaderAdmission;
  private final HostAdmission transferAdmission;
  private final Set<OwnedTask<?>> active = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object admission = new Object();
  @org.springframework.beans.factory.annotation.Autowired
  public WebFetchBatch(WebSearchProperties properties) {
    this(properties.getFetchParallelism(), properties.getFetchPerHost(), properties.getFetchQueueCapacity());
  }
  WebFetchBatch(int parallelism, int perHost, int queueCapacity) {
    if (parallelism < 1 || parallelism > 3 || perHost < 1 || perHost > parallelism || queueCapacity < 1 || queueCapacity > 64)
      throw new IllegalArgumentException("Invalid bounded HTTP executor configuration");
    loaderAdmission = new HostAdmission(perHost); transferAdmission = new HostAdmission(perHost);
    executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().daemon().name("web-fetch-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
  public <T> List<Outcome<T>> fetch(List<WebSearchSelection.Candidate> candidates, Duration timeout, Fetcher<T> loader) {
    if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0
        || candidates.size() > 20) throw new IllegalArgumentException("Invalid HTTP batch limits");
    FetchOperation parent = FetchScope.current(); parent.check();
    FetchOperation operation = parent.withTimeout(timeout);
    List<OwnedTask<T>> tasks = new ArrayList<>();
    try {
      for (var candidate : candidates) {
        operation.check();
        String host = URI.create(candidate.result().url()).getHost().toLowerCase(Locale.ROOT);
        var task = new OwnedTask<T>(() -> load(candidate, loader, host, operation));
        tasks.add(task); active.add(task);
        try { synchronized (admission) {
          if (closed.get()) throw new RejectedExecutionException(); executor.execute(task);
        } }
        catch (RejectedExecutionException rejected) { task.rejected = true; task.cancel(false); }
      }
      while (tasks.stream().anyMatch(task -> !task.isDone())) {
        for (var task : tasks) if (task.isDone() && !task.isCancelled()) outcome(task);
        operation.check();
        try { Thread.sleep(10); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
      }
    } catch (HttpFetchException timeoutFailure) {
      if (timeoutFailure.code() != HttpFetchException.Code.TOTAL_TIMEOUT) throw timeoutFailure;
    } finally {
      for (var task : tasks) if (!task.isDone()) task.cancel(true);
      // Future cancellation is not worker exit. Keep admission until the loader's finally has run.
      long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      boolean interrupted = Thread.interrupted();
      for (var task : tasks) {
        try { task.exited.await(Math.max(0, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException pending) { interrupted = true; }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    try { parent.check(); }
    catch (HttpFetchException timeoutFailure) {
      if (timeoutFailure.code() != HttpFetchException.Code.TOTAL_TIMEOUT) throw timeoutFailure;
    }
    List<Outcome<T>> results = new ArrayList<>(tasks.stream().map(this::outcome).toList());
    while (results.size() < candidates.size()) results.add(new Outcome<>(Status.TIMEOUT, null, "BATCH_TIMEOUT"));
    return List.copyOf(results);
  }
  private <T> Outcome<T> load(WebSearchSelection.Candidate candidate, Fetcher<T> loader, String host, FetchOperation operation) throws Exception {
    try (var lease = loaderAdmission.acquire(host, operation); var scope = FetchScope.enter(operation, transferAdmission)) {
      operation.check();
      T value = loader.fetch(candidate); operation.check();
      return new Outcome<>(Status.SUCCESS, value, null);
    } catch (Exception failure) {
      FetchOperation.propagateControls(failure);
      if (failure instanceof HttpFetchException http)
        return new Outcome<>(http.code().name().endsWith("TIMEOUT") ? Status.TIMEOUT : Status.FAILED, null, http.code().name());
      return new Outcome<>(Status.FAILED, null, "FETCH_ERROR");
    }
  }
  private <T> Outcome<T> outcome(OwnedTask<T> task) {
    if (task.rejected || closed.get() && task.isCancelled()) return new Outcome<>(Status.REJECTED, null, "FETCH_REJECTED");
    if (task.isCancelled()) return new Outcome<>(Status.TIMEOUT, null, "BATCH_TIMEOUT");
    try { return task.get(); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    catch (ExecutionException failed) { FetchOperation.propagateControls(failed); return new Outcome<>(Status.FAILED, null, "FETCH_ERROR"); }
  }
  private final class OwnedTask<T> extends FutureTask<Outcome<T>> {
    final AtomicInteger lifecycle = new AtomicInteger(); // queued, running, released
    final CountDownLatch exited = new CountDownLatch(1);
    volatile boolean rejected;
    OwnedTask(Callable<Outcome<T>> work) { super(work); }
    @Override public void run() {
      if (!lifecycle.compareAndSet(0, 1)) return;
      try { super.run(); } finally { if (lifecycle.compareAndSet(1, 2)) release(); }
    }
    @Override protected void done() {
      if (lifecycle.compareAndSet(0, 2)) { executor.remove(this); release(); }
    }
    private void release() {
      active.remove(this); exited.countDown();
    }
  }
  int activeTasks() { return active.size(); }
  int queuedTasks() { return executor.getQueue().size(); }
  int hostEntries() { return loaderAdmission.entries() + transferAdmission.entries(); }
  boolean terminated() { return executor.isTerminated(); }
  @jakarta.annotation.PreDestroy @Override public void close() {
    synchronized (admission) {
      closed.set(true); active.forEach(task -> task.cancel(true));
      for (var queued : executor.shutdownNow()) if (queued instanceof Future<?> future) future.cancel(true);
    }
    try { executor.awaitTermination(2, TimeUnit.SECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
  }
}
