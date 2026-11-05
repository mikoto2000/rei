package dev.mikoto2000.rei.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** SQLite fixture for benchmarks that exercise only the reading state table. */
public final class ReadingBenchmarkDatabase {
  private ReadingBenchmarkDatabase() {}

  public static StorageDatabase prepare(Path directory) throws Exception {
    Files.createDirectories(directory);
    var file = directory.resolve("storage.db");
    try (var connection =
            DriverManager.getConnection("jdbc:sqlite:" + file.toUri().toASCIIString());
        var sql = connection.createStatement()) {
      sql.executeQuery("PRAGMA journal_mode=WAL").close();
      ReadingStateMigration.apply(connection);
      sql.execute("PRAGMA user_version=" + StorageMigrationCoordinator.SCHEMA_VERSION);
    }
    return new StorageDatabase(directory);
  }

  public static AutoCloseable keepOpen(StorageDatabase database) throws Exception {
    return database.keepWalOpen();
  }
}
