package dev.mikoto2000.rei.storage;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.sql.Connection;
import java.time.Duration;

/**
 * データ保存の共通処理に必要な保存保守を扱う。
 *
 * <p>Observation is read-only. Space reclamation requires an explicit operation.
 */
public final class StorageMaintenance {
  /**
   * データ保存の共通処理で受け渡す状態を表す。
   *
   * @param databaseBytes データベースバイト数
   * @param walBytes walバイト数
   * @param temporaryBytes 一時的なバイト数
   * @param pageSize 一回で返す件数
   * @param pageCount ページ件数
   * @param freePages 解放ページ一覧
   * @param estimatedUsedPageBytes 推定使用量ページバイト数
   * @param autoVacuum 自動保存領域の整理
   * @param usableDiskBytes 利用可能Diskバイト数
   */
  public record Status(
      long databaseBytes,
      long walBytes,
      long temporaryBytes,
      long pageSize,
      long pageCount,
      long freePages,
      long estimatedUsedPageBytes,
      int autoVacuum,
      long usableDiskBytes) {}

  /**
   * データ保存の共通処理で受け渡す再開用の記録を表す。
   *
   * @param busy 使用中
   * @param logFrames ログ観測単位一覧
   * @param checkpointedFrames checkpointed観測単位一覧
   */
  public record Checkpoint(int busy, long logFrames, long checkpointedFrames) {}

  /** 登録台帳。 */
  private final StorageObjectRegistry registry;

  /** 指定された値や利用先を使って、StorageMaintenanceを初期化する。 */
  public StorageMaintenance(StorageObjectRegistry registry) {
    this.registry = registry;
  }

  /** データ保存の共通処理で用いる大きさを返す。 */
  private long size(String name) throws Exception {
    var path = registry.root.resolve(name);
    StorageBackup.requireSafePath(path);
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
  }

  /** データ保存の共通処理で用いるpragmaを返す。 */
  private static long pragma(Connection db, String name) throws Exception {
    try (var query = db.createStatement();
        var rows = query.executeQuery("PRAGMA " + name)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public Status status() {
    return registry.database.read(
        db -> {
          long pageSize = pragma(db, "page_size"),
              pages = pragma(db, "page_count"),
              free = pragma(db, "freelist_count");
          return new Status(
              size("storage.db"),
              size("storage.db-wal"),
              size("storage.db-journal"),
              pageSize,
              pages,
              free,
              Math.multiplyExact(pages - free, pageSize),
              (int) pragma(db, "auto_vacuum"),
              Files.getFileStore(registry.root).getUsableSpace());
        });
  }

  /** データ保存の共通処理で用いる再開用の記録を返す。 */
  private static Checkpoint checkpoint(Connection db) throws Exception {
    try (var query = db.createStatement();
        var rows = query.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")) {
      rows.next();
      return new Checkpoint(rows.getInt(1), rows.getLong(2), rows.getLong(3));
    }
  }

  /** データ保存の共通処理で用いる再開用の記録を返す。 */
  public Checkpoint checkpoint() {
    return registry.database.maintenance(StorageMaintenance::checkpoint);
  }

  /** データ保存の共通処理において、incremental保存領域の整理に該当するか判定する。 */
  public boolean incrementalVacuum(int pages) {
    if (pages < 1 || pages > 10000)
      throw new IllegalArgumentException("Incremental vacuum requires 1..10,000 pages");
    return registry.database.maintenance(
        db -> {
          if (pragma(db, "auto_vacuum") != 2) return false;
          try (var query = db.createStatement()) {
            query.execute("PRAGMA incremental_vacuum(" + pages + ")");
          }
          return true;
        });
  }

  /** データ保存の共通処理に関する保存領域の整理を処理する。 */
  public void vacuum(Duration window) {
    if (window == null || window.toSeconds() < 1 || window.toSeconds() > 3600)
      throw new IllegalArgumentException("Explicit maintenance window must be 1..3600 seconds");
    registry.database.maintenance(
        db -> {
          try (var query = db.createStatement();
              var rows =
                  query.executeQuery(
                      "SELECT 1 FROM turns WHERE status='RUNNING' UNION ALL SELECT 1 FROM conversation_admissions WHERE active=1 LIMIT 1")) {
            if (rows.next()) throw new IllegalStateException("Active Run; reschedule maintenance");
          }
          long required =
              Math.addExact(
                  Math.addExact(Math.multiplyExact(size("storage.db"), 2), size("storage.db-wal")),
                  64L * 1024 * 1024);
          if (Files.getFileStore(registry.root).getUsableSpace() < required)
            throw new IllegalStateException("Insufficient disk space for VACUUM");
          var checkpoint = checkpoint(db);
          if (checkpoint.busy() != 0 || checkpoint.logFrames() > checkpoint.checkpointedFrames())
            throw new IllegalStateException("Reader prevents checkpoint; reschedule maintenance");
          long deadline = System.nanoTime() + window.toNanos();
          org.sqlite.ProgressHandler.setHandler(
              db,
              10000,
              new org.sqlite.ProgressHandler() {
                /** データ保存の共通処理で用いる進捗を返す。 */
                @Override
                protected int progress() {
                  return Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline
                      ? 1
                      : 0;
                }
              });
          try (var query = db.createStatement()) {
            query.execute("PRAGMA busy_timeout=" + Math.min(5000, window.toMillis()));
            query.execute("VACUUM");
          } finally {
            org.sqlite.ProgressHandler.clearHandler(db);
          }
          return null;
        });
  }
}
