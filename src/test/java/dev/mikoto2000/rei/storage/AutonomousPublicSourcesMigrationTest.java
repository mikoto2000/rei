package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousPublicSourcesMigrationTest {
  @TempDir Path root;

  private void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
  }

  @Test
  void startupCreatesDedicatedPublicSourceVersions() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var tables = db.getMetaData().getTables(null, null, "autonomous_public_sources", null)) {
      assertThat(tables.next()).isTrue();
    }
  }

  @Test
  void version23UpgradePreservesExistingOpportunitiesAndDoesNotRepeat() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute(
          "INSERT INTO autonomous_opportunities(id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason,draft_id) VALUES('existing-op','MANUAL','NEW','POLICY_SKIPPED',NULL,0,0,0,'UTC','','','disabled',NULL)");
      sql.execute("DROP TABLE autonomous_public_sources");
      sql.execute("DELETE FROM storage_migrations WHERE version>=24");
      sql.execute("PRAGMA user_version=23");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNotNull();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement();
        var rows =
            sql.executeQuery(
                "SELECT COUNT(*) FROM autonomous_opportunities WHERE id='existing-op'")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isEqualTo(1);
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNull();
    }
  }

  @Test
  void missingCurrentSchemaTableStopsStartup() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE autonomous_public_sources");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);
    }
  }

  @Test
  void databaseAllowsOnlyOneCurrentVersionOfEachSource() throws Exception {
    prepare();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute(
          "INSERT INTO autonomous_public_sources VALUES('topic',1,1,'public topic',1,0,'operator')");
      assertThatThrownBy(() ->
              sql.execute(
                  "INSERT INTO autonomous_public_sources VALUES('topic',2,1,'replacement',1,1,'operator')"))
          .isInstanceOf(java.sql.SQLException.class);
      sql.execute(
          "INSERT INTO autonomous_public_sources VALUES('topic',2,0,'historical',0,1,'operator')");
    }
  }
}
