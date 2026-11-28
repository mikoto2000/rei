package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousPublicationStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousNotificationMigrationTest {
  @TempDir Path root;
  private final TestClock clock = new TestClock();

  private static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-11-28T09:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

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

  private SqliteAutonomousDraftStore store() {
    var defaults = AutonomousActionProperties.Bluesky.defaults();
    var settings =
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                defaults.cron(),
                defaults.zone(),
                10,
                10,
                Duration.ofMillis(1),
                defaults.draftExpiration(),
                defaults.maxRegenerations(),
                defaults.regenerationCooldown(),
                defaults.systemPrompt()));
    return new SqliteAutonomousDraftStore(new StorageDatabase(root), clock, settings);
  }

  private AutonomousDraft draft(SqliteAutonomousDraftStore store, String opportunity) {
    clock.now = clock.now.plusMillis(2);
    var claim =
        store
            .reserve(opportunity, AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    return store.complete(claim, "公開を確認する本文", "PRIVATE_REASON", List.of()).orElseThrow();
  }

  @Test
  void version26UpgradeBackfillsReviewAndResolutionWithoutChangingOwnedRecords() throws Exception {
    prepare();
    var store = store();
    var pending = draft(store, "pending");
    var rejectedDraft = draft(store, "rejected");
    var rejected = store.reject(rejectedDraft.id(), 1, "PRIVATE_FEEDBACK");
    var unknownDraft = draft(store, "unknown");
    var claim = store.beginPublication(unknownDraft.id(), 1, "fixture");
    var unknown =
        store.finishPublication(
            claim, AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
    var sources = new SqliteAutonomousPublicSources(new StorageDatabase(root), clock);
    var topic = sources.register("公開利用を許可した話題", true, "fixture");
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE IF EXISTS autonomous_notification_outbox");
      sql.execute("DELETE FROM storage_migrations WHERE version>=27");
      sql.execute("PRAGMA user_version=26");
    }
    var result = prepare();
    assertThat(result.schemaVersion()).isEqualTo(27);
    assertThat(result.backup()).isNotNull();
    assertThat(store.get(pending.id())).isEqualTo(pending);
    assertThat(store.get(rejected.id())).isEqualTo(rejected);
    assertThat(store.get(unknown.id())).isEqualTo(unknown);
    assertThat(store.publicationRecord(unknown.id())).contains(claim.record());
    assertThat(sources.get(topic.id())).isEqualTo(topic);
    try (var db = connection();
        var sql =
            db.prepareStatement(
                "SELECT review_required,created,pending,delivered_at FROM autonomous_notification_outbox WHERE draft_id=?")) {
      for (var saved : List.of(pending, rejected, unknown)) {
        sql.setString(1, saved.id());
        try (var row = sql.executeQuery()) {
          assertThat(row.next()).isTrue();
          assertThat(row.getInt("review_required"))
              .isEqualTo(saved.state() == AutonomousDraft.State.PENDING_REVIEW ? 1 : 0);
          assertThat(row.getLong("created")).isEqualTo(saved.createdAt().toEpochMilli());
          assertThat(row.getInt("pending")).isEqualTo(1);
          assertThat(row.getObject("delivered_at")).isNull();
        }
      }
    }
    assertThat(prepare().backup()).isNull();
  }

  @Test
  void noticeRowsRequireOwnedDraftsAndValidDeliveryState() throws Exception {
    prepare();
    var saved = draft(store(), "constraints");
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("DELETE FROM autonomous_notification_outbox");
      sql.execute(
          "INSERT INTO autonomous_notification_outbox VALUES('" + saved.id() + "',1,0,1,NULL)");
      assertThatThrownBy(() ->
              sql.execute(
                  "INSERT INTO autonomous_notification_outbox VALUES('"
                      + saved.id()
                      + "',1,0,1,NULL)"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("UNIQUE");
      assertThatThrownBy(() ->
              sql.execute(
                  "INSERT INTO autonomous_notification_outbox VALUES('missing',1,0,1,NULL)"))
          .isInstanceOf(java.sql.SQLException.class)
          .hasMessageContaining("FOREIGN KEY");
      for (var assignment :
          List.of(
              "review_required=2",
              "review_required='yes'",
              "pending=2",
              "pending=0,delivered_at=NULL",
              "created=1.5",
              "delivered_at='invalid'")) {
        assertThatThrownBy(
                () -> sql.executeUpdate("UPDATE autonomous_notification_outbox SET " + assignment))
            .as(assignment)
            .isInstanceOf(java.sql.SQLException.class);
      }
    }
  }

  @Test
  void missingCurrentOutboxStopsStartupInsteadOfLosingNotices() throws Exception {
    prepare();
    try (var db = connection();
        var sql = db.createStatement()) {
      sql.execute("DROP TABLE IF EXISTS autonomous_notification_outbox");
    }
    assertThatThrownBy(this::prepare).isInstanceOf(java.io.IOException.class);
  }
}
