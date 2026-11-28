package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousRegenerationMigrationTest {
  @TempDir Path root;

  private StorageMigrationCoordinator.Result prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      return migration.prepare();
    }
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
  }

  private boolean hasRevision(Connection db) throws Exception {
    try (var rows =
        db.getMetaData().getColumns(null, null, "autonomous_opportunities", "expected_revision")) {
      return rows.next();
    }
  }

  private void insertLegacyOpportunity(Connection db) throws Exception {
    try (var sql = db.createStatement()) {
      sql.execute(
          """
          INSERT INTO autonomous_opportunities
          (id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason)
          VALUES('existing-op','MANUAL','NEW','POLICY_SKIPPED',NULL,0,0,0,'UTC','','','disabled')
          """);
    }
  }

  @Test
  void reservationRevisionIsNullableForOldRowsAndPositiveWhenSpecified() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      assertThat(hasRevision(db)).isTrue();
      insertLegacyOpportunity(db);
      try (var rows = sql.executeQuery("SELECT expected_revision FROM autonomous_opportunities")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getObject(1)).isNull();
      }
      assertThatThrownBy(
              () -> sql.execute("UPDATE autonomous_opportunities SET expected_revision=0"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () -> sql.execute("UPDATE autonomous_opportunities SET expected_revision=-1"))
          .isInstanceOf(java.sql.SQLException.class);
      assertThatThrownBy(
              () -> sql.execute("UPDATE autonomous_opportunities SET expected_revision=1.5"))
          .isInstanceOf(java.sql.SQLException.class);
      sql.execute("UPDATE autonomous_opportunities SET expected_revision=7");
    }
  }

  @Test
  void version24UpgradePreservesOpportunitiesAndTopicsAndRunsOnce() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      insertLegacyOpportunity(db);
      sql.execute(
          "INSERT INTO autonomous_public_sources VALUES('topic',1,1,'public topic',1,0,'operator')");
      if (hasRevision(db))
        sql.execute("ALTER TABLE autonomous_opportunities DROP COLUMN expected_revision");
      sql.execute("DELETE FROM storage_migrations WHERE version>=25");
      sql.execute("PRAGMA user_version=24");
    }
    var upgraded = prepare();
    assertThat(upgraded.schemaVersion()).isEqualTo(25);
    assertThat(upgraded.backup()).isNotNull();
    try (var db = connection();
        var sql = db.createStatement()) {
      assertThat(hasRevision(db)).isTrue();
      try (var rows =
          sql.executeQuery(
              "SELECT reason,expected_revision FROM autonomous_opportunities WHERE id='existing-op'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("disabled");
        assertThat(rows.getObject(2)).isNull();
      }
      try (var rows =
          sql.executeQuery("SELECT body FROM autonomous_public_sources WHERE id='topic'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("public topic");
      }
    }
    assertThat(prepare().backup()).isNull();
  }

  @Test
  void missingRevisionColumnInCurrentSchemaStopsStartup() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("ALTER TABLE autonomous_opportunities DROP COLUMN expected_revision");
    }
    assertThatThrownBy(this::prepare)
        .isInstanceOf(java.io.IOException.class)
        .hasStackTraceContaining("expected_revision");
  }
}
