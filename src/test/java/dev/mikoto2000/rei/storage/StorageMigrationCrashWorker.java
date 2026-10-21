package dev.mikoto2000.rei.storage;

/**
 * 検証条件を再現するための保存移行Crash実行の担当。
 *
 * <p>Child JVM fixture: killed inside the schema transaction after DDL, before commit.
 */
public final class StorageMigrationCrashWorker {
  /** 検証用に、データ保存の共通処理の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length > 1 && arguments[1].equals("hot-journal")) {
      try (var lease = new StorageMigrationCoordinator(java.nio.file.Path.of(arguments[0]));
          var db =
              java.sql.DriverManager.getConnection(
                  "jdbc:sqlite:" + java.nio.file.Path.of(arguments[0]).resolve("storage.db"));
          var query = db.createStatement()) {
        query.execute("PRAGMA cache_size=10");
        db.setAutoCommit(false);
        query.execute("CREATE TABLE partial(value BLOB)");
        query.execute("INSERT INTO partial VALUES(zeroblob(8388608))");
        System.out.println("SPILLED");
        System.out.flush();
        System.in.read();
      }
      return;
    }
    try (var coordinator =
        new StorageMigrationCoordinator(
            java.nio.file.Path.of(arguments[0]),
            (stage, backup) -> {
              String pause =
                  arguments.length > 1 && arguments[1].equals("rows-imported")
                      ? "ROWS_IMPORTED"
                      : "SCHEMA_WRITTEN";
              if (stage.name().equals(pause)) {
                System.out.println("APPLYING");
                System.out.flush();
                System.in.read();
              }
            })) {
      coordinator.prepare();
    }
  }
}
