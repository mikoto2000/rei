package dev.mikoto2000.rei.storage;

import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Shell が開く前に行われる保存移行の状態を、ログ設定によらず画面に表示する。 */
final class StorageMigrationProgress implements AutoCloseable {
  private final PrintStream output;
  private final long started = System.nanoTime();
  private final ScheduledExecutorService timer;
  private String phase = "移行対象を確認中";
  private boolean closed;

  StorageMigrationProgress(int sourceVersion, int targetVersion) {
    this(System.err, sourceVersion, targetVersion, Duration.ofSeconds(15));
  }

  StorageMigrationProgress(
      PrintStream output, int sourceVersion, int targetVersion, Duration interval) {
    this.output = output;
    output.printf(
        "[storage] 保存データを更新します（%d → %d）。Shell は完了後に開きます。データ量によって数分以上かかります。%n",
        sourceVersion, targetVersion);
    step(phase);
    timer =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("storage-migration-progress").factory());
    timer.scheduleAtFixedRate(
        this::report, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  synchronized void step(String phase) {
    this.phase = phase;
    report();
  }

  private synchronized void report() {
    if (!closed) {
      long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
      output.printf("[storage] %s（経過 %d 秒）%n", phase, elapsed);
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    timer.shutdownNow();
  }
}
