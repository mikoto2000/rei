package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.http.FetchOperation;
import dev.mikoto2000.rei.http.FetchScope;
import dev.mikoto2000.rei.http.HostAdmission;
import dev.mikoto2000.rei.http.HttpFetchException;
import dev.mikoto2000.rei.http.TransferBudget;
import dev.mikoto2000.rei.http.TransferScope;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Web上の情報の調査に必要なWeb取得まとめた処理を扱う。
 *
 * <p>One shared bounded HTTP worker pool; caller deadlines and rank survive executor boundaries.
 */
@Component
public final class WebFetchBatch implements AutoCloseable {
  /** Web上の情報の調査における状態の選択肢。 */
  public enum Status {
    /** 成功。 */
    SUCCESS,
    /** 失敗。 */
    FAILED,
    /** タイムアウト。 */
    TIMEOUT,
    /** 拒否済み。 */
    REJECTED
  }

  /**
   * Web上の情報の調査で受け渡す最終結果を表す。
   *
   * @param status 状態
   * @param value 値
   * @param errorType エラー種別
   */
  public record Outcome<T>(Status status, T value, String errorType) {
    /** 処理が成功したことを、呼び出し元へ伝える情報として表す。 */
    public boolean success() {
      return status == Status.SUCCESS;
    }
  }

  /** Web上の情報の調査に必要な情報の取得元を扱う。 */
  @FunctionalInterface
  public interface Fetcher<T> {
    /** 後続の処理に必要な情報を、取得先から読み込む。 */
    T fetch(WebSearchSelection.Candidate candidate) throws Exception;
  }

  /** Web上の情報の調査に必要な既定値を扱う。 */
  private static final class Defaults {
    /** 起動単位の識別子。 */
    static final WebFetchBatch INSTANCE = new WebFetchBatch(3, 2, 32);
  }

  /** Web上の情報の調査で用いる共有既定値を返す。 */
  static WebFetchBatch sharedDefaults() {
    return Defaults.INSTANCE;
  }

  /** 実行先。 */
  private final ThreadPoolExecutor executor;

  /** 読み込みの窓口受付。 */
  private final HostAdmission loaderAdmission;

  /** 引き継ぎ受付。 */
  private final HostAdmission transferAdmission;

  /** 活動中。 */
  private final Set<OwnedTask<?>> active = ConcurrentHashMap.newKeySet();

  /** 終了済み。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 受付。 */
  private final Object admission = new Object();

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebFetchBatch(WebSearchProperties properties) {
    this(
        properties.getFetchParallelism(),
        properties.getFetchPerHost(),
        properties.getFetchQueueCapacity());
  }

  /** 指定された値や利用先を使って、WebFetchBatchを初期化する。 */
  WebFetchBatch(int parallelism, int perHost, int queueCapacity) {
    if (parallelism < 1
        || parallelism > 3
        || perHost < 1
        || perHost > parallelism
        || queueCapacity < 1
        || queueCapacity > 64)
      throw new IllegalArgumentException("Invalid bounded HTTP executor configuration");
    loaderAdmission = new HostAdmission(perHost);
    transferAdmission = new HostAdmission(perHost);
    executor =
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            Thread.ofPlatform().daemon().name("web-fetch-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /** 後続の処理に必要な情報を、取得先から読み込む。 */
  public <T> List<Outcome<T>> fetch(
      List<WebSearchSelection.Candidate> candidates, Duration timeout, Fetcher<T> loader) {
    if (timeout == null
        || timeout.isNegative()
        || timeout.isZero()
        || timeout.compareTo(Duration.ofMinutes(5)) > 0
        || candidates.size() > 20) throw new IllegalArgumentException("Invalid HTTP batch limits");
    FetchOperation parent = FetchScope.current();
    parent.check();
    FetchOperation operation = parent.withTimeout(timeout);
    boolean refresh = FetchScope.forceRefresh();
    var transferBudget = TransferScope.current();
    String query = WebExtractionScope.query();
    List<OwnedTask<T>> tasks = new ArrayList<>();
    try {
      for (var candidate : candidates) {
        operation.check();
        String host = URI.create(candidate.result().url()).getHost().toLowerCase(Locale.ROOT);
        var task =
            new OwnedTask<T>(
                () -> load(candidate, loader, host, operation, refresh, transferBudget, query));
        tasks.add(task);
        active.add(task);
        try {
          synchronized (admission) {
            if (closed.get()) throw new RejectedExecutionException();
            executor.execute(task);
          }
        } catch (RejectedExecutionException rejected) {
          task.rejected = true;
          task.cancel(false);
        }
      }
      while (tasks.stream().anyMatch(task -> !task.isDone())) {
        for (var task : tasks) if (task.isDone() && !task.isCancelled()) outcome(task);
        operation.check();
        try {
          Thread.sleep(10);
        } catch (InterruptedException interrupted) {
          // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
          Thread.currentThread().interrupt();
          throw new CancellationException();
        }
      }
    } catch (HttpFetchException timeoutFailure) {
      if (timeoutFailure.code() != HttpFetchException.Code.TOTAL_TIMEOUT) throw timeoutFailure;
    } finally {
      for (var task : tasks) if (!task.isDone()) task.cancel(true);
      // Future cancellation is not worker exit. Keep admission until the loader's finally has run.
      long cleanupDeadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
      boolean interrupted = Thread.interrupted();
      for (var task : tasks) {
        try {
          task.exited.await(Math.max(0, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException pending) {
          interrupted = true;
        }
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
    try {
      parent.check();
    } catch (HttpFetchException timeoutFailure) {
      if (timeoutFailure.code() != HttpFetchException.Code.TOTAL_TIMEOUT) throw timeoutFailure;
    }
    List<Outcome<T>> results = new ArrayList<>(tasks.stream().map(this::outcome).toList());
    while (results.size() < candidates.size())
      results.add(new Outcome<>(Status.TIMEOUT, null, "BATCH_TIMEOUT"));
    return List.copyOf(results);
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  private <T> Outcome<T> load(
      WebSearchSelection.Candidate candidate,
      Fetcher<T> loader,
      String host,
      FetchOperation operation,
      boolean refresh,
      TransferBudget transferBudget,
      String query)
      throws Exception {
    try (var lease = loaderAdmission.acquire(host, operation);
        var scope = FetchScope.enter(operation, transferAdmission, refresh);
        var transfer = TransferScope.enter(transferBudget);
        var extraction = WebExtractionScope.enter(query)) {
      operation.check();
      T value = loader.fetch(candidate);
      operation.check();
      return new Outcome<>(Status.SUCCESS, value, null);
    } catch (Exception failure) {
      FetchOperation.propagateControls(failure);
      if (failure instanceof HttpFetchException http)
        return new Outcome<>(
            http.code().name().endsWith("TIMEOUT") ? Status.TIMEOUT : Status.FAILED,
            null,
            http.code().name());
      return new Outcome<>(Status.FAILED, null, "FETCH_ERROR");
    }
  }

  /** Web上の情報の調査で用いる最終結果を返す。 */
  private <T> Outcome<T> outcome(OwnedTask<T> task) {
    if (task.rejected || closed.get() && task.isCancelled())
      return new Outcome<>(Status.REJECTED, null, "FETCH_REJECTED");
    if (task.isCancelled()) return new Outcome<>(Status.TIMEOUT, null, "BATCH_TIMEOUT");
    try {
      return task.get();
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException();
    } catch (ExecutionException failed) {
      FetchOperation.propagateControls(failed);
      return new Outcome<>(Status.FAILED, null, "FETCH_ERROR");
    }
  }

  /** Web上の情報の調査に必要な自分が所有する対象タスクを扱う。 */
  private final class OwnedTask<T> extends FutureTask<Outcome<T>> {
    /** 受付から終了までの管理。 */
    final AtomicInteger lifecycle = new AtomicInteger(); // queued, running, released

    /** 終了済み。 */
    final CountDownLatch exited = new CountDownLatch(1);

    /** 拒否済み。 */
    volatile boolean rejected;

    /** 指定された値や利用先を使って、OwnedTaskを初期化する。 */
    OwnedTask(Callable<Outcome<T>> work) {
      super(work);
    }

    /** Web上の情報の調査の操作を実行する。 */
    @Override
    public void run() {
      if (!lifecycle.compareAndSet(0, 1)) return;
      try {
        super.run();
      } finally {
        if (lifecycle.compareAndSet(1, 2)) release();
      }
    }

    /** Web上の情報の調査に関する完了を処理する。 */
    @Override
    protected void done() {
      if (lifecycle.compareAndSet(0, 2)) {
        executor.remove(this);
        release();
      }
    }

    /** 後続の処理が利用できるよう、自分が所有する対象タスクを解放する。 */
    private void release() {
      active.remove(this);
      exited.countDown();
    }
  }

  /** Web上の情報の調査で用いる活動中タスク一覧を返す。 */
  int activeTasks() {
    return active.size();
  }

  /** Web上の情報の調査で用いる待機中タスク一覧を返す。 */
  int queuedTasks() {
    return executor.getQueue().size();
  }

  /** Web上の情報の調査で用いるホスト項目一覧を返す。 */
  int hostEntries() {
    return loaderAdmission.entries() + transferAdmission.entries();
  }

  /** Web上の情報の調査において、終了済みに該当するか判定する。 */
  boolean terminated() {
    return executor.isTerminated();
  }

  /** Web上の情報の調査で利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    synchronized (admission) {
      closed.set(true);
      active.forEach(task -> task.cancel(true));
      for (var queued : executor.shutdownNow())
        if (queued instanceof Future<?> future) future.cancel(true);
    }
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }
}
