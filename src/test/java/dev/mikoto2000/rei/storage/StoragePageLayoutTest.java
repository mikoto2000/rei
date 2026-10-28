package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ページ再構築の全値照合・バックアップ・中断再開を実SQLiteで検証する。 */
@Tag("integration")
class StoragePageLayoutTest {
  @TempDir Path root;

  private Connection connection() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
  }

  private void prepareLegacy() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("PRAGMA synchronous=FULL");
      sql.execute("PRAGMA journal_mode=DELETE");
      sql.execute("PRAGMA page_size=4096");
      sql.execute("VACUUM");
      sql.execute(
          "CREATE TABLE \"fixture\"\"quoted\"(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT COLLATE NOCASE,number,bytes BLOB)");
      try (var insert =
          db.prepareStatement(
              "INSERT INTO \"fixture\"\"quoted\"(id,body,number,bytes) VALUES(?,?,?,?)")) {
        insert.setLong(1, 7);
        insert.setString(2, "日本語\u0000A");
        insert.setDouble(3, 1.2345678901234567);
        insert.setBytes(4, new byte[] {0, -1, 5, 0});
        insert.executeUpdate();
      }
      sql.executeUpdate(
          "INSERT INTO \"fixture\"\"quoted\"(id,body,number) VALUES(8,'a',9223372036854775807)");
      sql.executeUpdate("INSERT INTO \"fixture\"\"quoted\"(id,body,number) VALUES(9,NULL,NULL)");
      sql.executeUpdate("INSERT INTO \"fixture\"\"quoted\"(id,body) VALUES(100,'deleted')");
      sql.executeUpdate("DELETE FROM \"fixture\"\"quoted\" WHERE id=100");
      sql.executeUpdate("DELETE FROM storage_migrations WHERE version>=18");
      sql.execute("PRAGMA user_version=17");
    }
  }

  private void verifyValues() throws Exception {
    try (var db = connection();
        var sql = db.createStatement()) {
      try (var rows = sql.executeQuery("PRAGMA page_size")) {
        assertThat(rows.getInt(1)).isEqualTo(1024);
      }
      try (var rows = sql.executeQuery("SELECT * FROM \"fixture\"\"quoted\" ORDER BY id")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("id")).isEqualTo(7);
        assertThat(rows.getString("body")).isEqualTo("日本語\u0000A");
        assertThat(rows.getDouble("number")).isEqualTo(1.2345678901234567);
        assertThat(rows.getBytes("bytes")).containsExactly(0, -1, 5, 0);
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong("number")).isEqualTo(Long.MAX_VALUE);
        assertThat(rows.next()).isTrue();
        assertThat(rows.getObject("number")).isNull();
        assertThat(rows.next()).isFalse();
      }
      sql.executeUpdate("INSERT INTO \"fixture\"\"quoted\"(body) VALUES('after')");
      try (var rows = sql.executeQuery("SELECT max(id) FROM \"fixture\"\"quoted\"")) {
        assertThat(rows.getLong(1)).isEqualTo(101);
      }
    }
  }

  @Test
  void migrationKeepsVerifiedOriginalBackupAndEveryTypedValueAndSequence() throws Exception {
    prepareLegacy();
    String original;
    try (var db = connection()) {
      original = StoragePageLayout.contents(db);
    }
    Path backup;
    try (var migration = new StorageMigrationCoordinator(root)) {
      backup = migration.prepare().backup();
      assertThat(backup).isNotNull();
    }
    StorageBackup.verify(backup);
    try (var db = StorageBackup.readOnly(backup.resolve("files/storage.db"))) {
      assertThat(StoragePageLayout.contents(db)).isEqualTo(original);
      try (var sql = db.createStatement();
          var rows = sql.executeQuery("PRAGMA page_size")) {
        assertThat(rows.getInt(1)).isEqualTo(4096);
      }
    }
    verifyValues();
  }

  @Test
  void logicalFingerprintCoversNulTextBlobRealIntegerAndNullAndIgnoresPhysicalPages()
      throws Exception {
    prepareLegacy();
    try (var db = connection()) {
      String before = StoragePageLayout.contents(db);
      StoragePageLayout.apply(db, root.resolve("storage.db"));
      assertThat(StoragePageLayout.contents(db)).isEqualTo(before);
      try (var sql = db.createStatement()) {
        sql.executeUpdate(
            "UPDATE \"fixture\"\"quoted\" SET body='日本語' || char(0) || 'B' WHERE id=7");
      }
      assertThat(StoragePageLayout.contents(db)).isNotEqualTo(before);
    }
  }

  @Test
  void interruptionAfterRepackBeforeSchemaCommitCanRetryWithoutLosingRows() throws Exception {
    prepareLegacy();
    var backup = new java.util.concurrent.atomic.AtomicReference<Path>();
    try (var migration =
        new StorageMigrationCoordinator(
            root,
            (stage, saved) -> {
              if (stage == StorageMigrationCoordinator.Stage.PAGES_REPACKED) {
                backup.set(saved);
                throw new java.io.IOException("interrupted after repack");
              }
            })) {
      assertThatThrownBy(migration::prepare).hasMessageContaining("interrupted after repack");
    }
    StorageBackup.verify(backup.get());
    try (var db = connection();
        var sql = db.createStatement();
        var rows = sql.executeQuery("PRAGMA user_version")) {
      assertThat(rows.getInt(1)).isEqualTo(17);
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    verifyValues();
  }

  @Test
  void forcedExitAfterRepackKeepsTheOldSchemaReadableAndRetriesSafely() throws Exception {
    prepareLegacy();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path arguments = root.resolve("repack-worker.args");
    java.nio.file.Files.writeString(
        arguments,
        "-cp\n"
            + quote(classpath)
            + "\n"
            + StorageMigrationCrashWorker.class.getName()
            + "\n"
            + quote(root.toString())
            + "\npages-repacked\n");
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    var child = new ProcessBuilder(executable, "@" + arguments).redirectErrorStream(true).start();
    try {
      var ready =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try (var reader = child.inputReader()) {
                  String line;
                  while ((line = reader.readLine()) != null)
                    if (line.equals("APPLYING")) return true;
                  return false;
                } catch (java.io.IOException failure) {
                  throw new java.io.UncheckedIOException(failure);
                }
              });
      assertThat(ready.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> new StorageMigrationCoordinator(root))
          .isInstanceOf(java.io.IOException.class);
    } finally {
      child.destroyForcibly();
      assertThat(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
    try (var db = connection();
        var sql = db.createStatement();
        var rows = sql.executeQuery("PRAGMA user_version")) {
      assertThat(rows.getInt(1)).isEqualTo(17);
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    verifyValues();
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Test
  void digestTreatsSchemaIdentifiersAsDataEvenWhenTheyContainSqlSyntax() throws Exception {
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("CREATE TABLE guard(value TEXT)");
      sql.execute("INSERT INTO guard VALUES('retained')");
      sql.execute(
          "CREATE TABLE \"payload; DROP TABLE guard;--\"(\"value\"\"; DELETE FROM guard;--\" TEXT)");
      sql.execute("INSERT INTO \"payload; DROP TABLE guard;--\" VALUES('before')");
      String before = StoragePageLayout.contents(db);
      sql.execute(
          "UPDATE \"payload; DROP TABLE guard;--\" SET \"value\"\"; DELETE FROM guard;--\"='after'");
      assertThat(StoragePageLayout.contents(db)).isNotEqualTo(before);
      try (var rows = sql.executeQuery("SELECT value FROM guard")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("retained");
        assertThat(rows.next()).isFalse();
      }
    }
  }

  @Test
  void upgradesPreviouslyBuiltPageOnlyVersionSeventeenWithoutLosingData() throws Exception {
    prepareLegacy();
    try (var db = connection();
        var sql = db.createStatement()) {
      StoragePageLayout.apply(db, root.resolve("storage.db"));
      sql.execute("DROP TABLE secretary_briefings");
    }
    java.nio.file.Files.writeString(
        root.resolve(".storage/migration-state.json"),
        "{\"sourceVersion\":0,\"targetVersion\":17,\"stage\":\"COMPLETE\",\"backup\":\"\"}");
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().schemaVersion())
          .isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
      assertThat(migration.prepare().backup()).isNotNull();
    }
    verifyValues();
    try (var db = connection()) {
      SecretaryBriefingMigration.verify(db);
    }
  }

  @Test
  void missingBriefingWithoutKnownPageOnlyCompletionStillStopsStartup() throws Exception {
    prepareLegacy();
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE secretary_briefings");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);
    }
  }
}
