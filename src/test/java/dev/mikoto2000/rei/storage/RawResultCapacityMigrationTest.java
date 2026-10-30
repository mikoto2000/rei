package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class RawResultCapacityMigrationTest {
  @TempDir Path root;

  private void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
  }

  private void indexedPlan() throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement();
        var rows =
            sql.executeQuery(
                "EXPLAIN QUERY PLAN SELECT COALESCE(SUM(size),0) FROM stored_objects WHERE kind='RAW_RESULT' AND status<>'PURGED'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString("detail")).contains("stored_objects_raw_capacity");
    }
  }

  @Test
  void capacityAndIndexFollowStatusesUpdatesDeletionAndRollback() throws Exception {
    prepare();
    var registry = new StorageObjectRegistry(root);
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute(
                "INSERT INTO stored_objects(id,kind,scope,relative_path,size,sha256,origin_verified,status) VALUES('a','RAW_RESULT','','a',10,'hash',1,'AVAILABLE'),('b','RAW_RESULT','','b',20,'hash',1,'QUARANTINED'),('c','RAW_RESULT','','c',40,'hash',1,'PURGED'),('e','EVENT','','e',999999,'hash',1,'AVAILABLE')");
          }
          return null;
        });
    assertThat(registry.rawStoredBytes()).isEqualTo(30);
    indexedPlan();
    registry.database.transaction(
        db -> {
          try (var sql = db.createStatement()) {
            sql.execute("UPDATE stored_objects SET status='PURGED' WHERE id='b'");
            sql.execute("UPDATE stored_objects SET size=12 WHERE id='a'");
          }
          return null;
        });
    assertThat(registry.rawStoredBytes()).isEqualTo(12);
    assertThatThrownBy(
            () ->
                registry.database.transaction(
                    db -> {
                      try (var sql = db.createStatement()) {
                        sql.execute("DELETE FROM stored_objects");
                      }
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(registry.rawStoredBytes()).isEqualTo(12);
  }

  @Test
  void version21UpgradeAddsIndexAndRestartDoesNotMigrateAgain() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP INDEX stored_objects_raw_capacity");
      sql.execute("DELETE FROM storage_migrations WHERE version=22");
      sql.execute("PRAGMA user_version=21");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNotNull();
    }
    indexedPlan();
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNull();
    }
  }

  @Test
  void missingIndexInCurrentSchemaStopsStartup() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP INDEX stored_objects_raw_capacity");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);
    }
  }
}
