package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousDraftMigrationTest {
  @TempDir Path root;

  @Test
  void startupCreatesDurableDraftAndOpportunityTables() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var tables = db.getMetaData().getTables(null, null, "autonomous_drafts", null)) {
      assertThat(tables.next()).isTrue();
    }
  }

  @Test
  void version22UpgradeIsBackedUpAndReadyRestartDoesNotMigrateAgain() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE autonomous_draft_revisions");
      sql.execute("DROP TABLE autonomous_drafts");
      sql.execute("DROP TABLE autonomous_opportunities");
      sql.execute("DROP TABLE autonomous_audit");
      sql.execute("DELETE FROM storage_migrations WHERE version=23");
      sql.execute("PRAGMA user_version=22");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNotNull();
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNull();
    }
  }

  @Test
  void aMissingCurrentSchemaTableStopsStartup() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE autonomous_audit");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThatThrownBy(migration::prepare).isInstanceOf(java.io.IOException.class);
    }
  }

  @Test
  void databaseRejectsConcurrentActiveClaimsAndIndependentApprovedState() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var sql = db.createStatement()) {
      sql.execute(
          "INSERT INTO autonomous_opportunities(id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason) VALUES('a','MANUAL','NEW','RUNNING','one',0,0,1,'UTC','did:plc:example','rei.example','test')");
      assertThatThrownBy(() ->
              sql.execute(
                  "INSERT INTO autonomous_opportunities(id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason) VALUES('b','SCHEDULED','NEW','RUNNING','two',0,0,1,'UTC','did:plc:example','rei.example','test')"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(() ->
              sql.execute(
                  "INSERT INTO autonomous_drafts VALUES('draft','a','APPROVED',1,0,0,1,'did:plc:example','hash','{}')"))
          .isInstanceOf(java.sql.SQLException.class);
    }
  }
}
