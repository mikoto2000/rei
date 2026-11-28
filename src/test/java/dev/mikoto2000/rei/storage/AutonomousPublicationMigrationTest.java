package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousPostRecord;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousPublicationMigrationTest {
  @TempDir Path root;
  private static final Instant AT = Instant.parse("2026-11-28T06:00:00Z");
  private static final String KEY = "3jzfcijpj2z2a";

  private StorageMigrationCoordinator.Result prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      return migration.prepare();
    }
  }

  private Connection connection() throws Exception {
    var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
    try (var sql = db.createStatement()) {
      sql.execute("PRAGMA foreign_keys=ON");
    }
    return db;
  }

  private AutonomousDraft draft(String id, String did) {
    return new AutonomousDraft(
        id,
        AutonomousDraft.ActionType.BLUESKY_POST,
        "op-" + id,
        AutonomousDraft.Trigger.MANUAL,
        AT,
        AT,
        new AutonomousDraft.Target(did, "rei.example"),
        AutonomousDraft.Content.create(3, "保存済みの本文", "private reason", List.of("topic:one:1")),
        AutonomousDraft.State.PENDING_REVIEW,
        AT.plusSeconds(7200),
        "",
        false,
        0,
        null,
        null,
        null);
  }

  private void insertDraft(Connection db, AutonomousDraft draft) throws Exception {
    try (var sql =
        db.prepareStatement(
            """
            INSERT INTO autonomous_opportunities
            (id,trigger,kind,status,token,created,updated,considered,zone,target_did,target_handle,reason,draft_id,expected_revision)
            VALUES(?,'MANUAL','NEW','GENERATED',NULL,0,0,1,'UTC',?,'rei.example','pending_review',?,NULL)
            """)) {
      sql.setString(1, draft.opportunityId());
      sql.setString(2, draft.target().did());
      sql.setString(3, draft.id());
      sql.executeUpdate();
    }
    try (var sql =
        db.prepareStatement("INSERT INTO autonomous_drafts VALUES(?,?,?,?,?,?,?,?,?,?)")) {
      sql.setString(1, draft.id());
      sql.setString(2, draft.opportunityId());
      sql.setString(3, draft.state().name());
      sql.setInt(4, draft.content().revision());
      sql.setLong(5, AT.toEpochMilli());
      sql.setLong(6, AT.toEpochMilli());
      sql.setLong(7, draft.expiresAt().toEpochMilli());
      sql.setString(8, draft.target().did());
      sql.setString(9, draft.content().bodyHash());
      sql.setString(10, StorageDatabase.JSON.writeValueAsString(draft));
      sql.executeUpdate();
    }
    try (var sql = db.prepareStatement("INSERT INTO autonomous_draft_revisions VALUES(?,?,?,?)")) {
      sql.setString(1, draft.id());
      sql.setInt(2, draft.content().revision());
      sql.setLong(3, AT.toEpochMilli());
      sql.setString(4, StorageDatabase.JSON.writeValueAsString(draft.content()));
      sql.executeUpdate();
    }
  }

  private void insertPublication(Connection db, AutonomousDraft draft, String key)
      throws Exception {
    var record = AutonomousPostRecord.fromDraft(draft, key, AT);
    try (var sql =
        db.prepareStatement(
            """
            INSERT INTO autonomous_publications
            (draft_id,target_did,record_key,revision,body_hash,record_json,record_hash,token,created,updated)
            VALUES(?,?,?,?,?,?,?,?,?,?)
            """)) {
      sql.setString(1, draft.id());
      sql.setString(2, draft.target().did());
      sql.setString(3, key);
      sql.setInt(4, draft.content().revision());
      sql.setString(5, draft.content().bodyHash());
      sql.setString(6, record.recordJson());
      sql.setString(7, record.recordHash());
      sql.setString(8, "claim-token-" + draft.id() + "-for-test");
      sql.setLong(9, AT.toEpochMilli());
      sql.setLong(10, AT.toEpochMilli());
      sql.executeUpdate();
    }
  }

  @Test
  void version25UpgradePreservesDraftsTopicsAndReservationsAndRunsOnce() throws Exception {
    prepare();
    var old = draft("old", "did:plc:example");
    try (var db = connection();
        var sql = db.createStatement()) {
      insertDraft(db, old);
      sql.execute(
          "INSERT INTO autonomous_public_sources VALUES('topic-one',1,1,'public topic',1,0,'operator')");
      sql.execute("DROP TABLE IF EXISTS autonomous_publications");
      sql.execute("DELETE FROM storage_migrations WHERE version>=26");
      sql.execute("PRAGMA user_version=25");
    }
    var upgraded = prepare();
    assertThat(upgraded.schemaVersion()).isEqualTo(26);
    assertThat(upgraded.backup()).isNotNull();
    try (var db = connection();
        var sql = db.createStatement()) {
      try (var rows =
          sql.executeQuery("SELECT snapshot,revision FROM autonomous_drafts WHERE id='old'")) {
        assertThat(rows.next()).isTrue();
        assertThat(StorageDatabase.JSON.readValue(rows.getString(1), AutonomousDraft.class))
            .isEqualTo(old);
        assertThat(rows.getInt(2)).isEqualTo(3);
      }
      try (var rows =
          sql.executeQuery(
              "SELECT reason,expected_revision FROM autonomous_opportunities WHERE id='op-old'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("pending_review");
        assertThat(rows.getObject(2)).isNull();
      }
      try (var rows =
          sql.executeQuery("SELECT body FROM autonomous_public_sources WHERE id='topic-one'")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("public topic");
      }
      try (var rows =
          sql.executeQuery(
              "SELECT snapshot FROM autonomous_draft_revisions WHERE draft_id='old' AND revision=3")) {
        assertThat(rows.next()).isTrue();
        assertThat(StorageDatabase.JSON.readValue(rows.getString(1), AutonomousDraft.Content.class))
            .isEqualTo(old.content());
      }
      try (var rows = sql.executeQuery("SELECT COUNT(*) FROM autonomous_publications")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isZero();
      }
    }
    assertThat(prepare().backup()).isNull();
  }

  @Test
  void journalBindsOneRecordPerDraftAndOneKeyPerTargetWithValidatedFields() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      var first = draft("first", "did:plc:example");
      var second = draft("second", "did:plc:example");
      var other = draft("other", "did:plc:other");
      insertDraft(db, first);
      insertDraft(db, second);
      insertDraft(db, other);
      insertPublication(db, first, KEY);
      assertThatThrownBy(() -> insertPublication(db, first, "3jzfcijpj2z2b"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("UNIQUE");
      assertThatThrownBy(() -> insertPublication(db, second, KEY))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("UNIQUE");
      insertPublication(db, other, KEY);
      assertThatThrownBy(
              () -> insertPublication(db, draft("missing", "did:plc:missing"), "3jzfcijpj2z2b"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("FOREIGN KEY");
      for (var assignment :
          List.of(
              "revision=0",
              "revision=-1",
              "revision=1.5",
              "revision=4",
              "record_key='not-a-tid'",
              "body_hash='invalid'",
              "record_hash='INVALID'",
              "record_json=''",
              "token=''")) {
        assertThatThrownBy(() ->
                sql.executeUpdate(
                    "UPDATE autonomous_publications SET " + assignment + " WHERE draft_id='first'"))
            .as(assignment)
            .isInstanceOf(java.sql.SQLException.class);
      }
      try (var rows = sql.executeQuery("SELECT COUNT(*) FROM autonomous_publications")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(2);
      }
      sql.executeUpdate("UPDATE autonomous_publications SET token=NULL");
    }
  }

  @Test
  void missingPublicationJournalInCurrentSchemaStopsStartup() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE IF EXISTS autonomous_publications");
    }
    assertThatThrownBy(this::prepare)
        .isInstanceOf(java.io.IOException.class)
        .hasStackTraceContaining("autonomous_publications");
  }
}
