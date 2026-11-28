package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class AutonomousNotificationStoreTest {
  private static final String CID = "bafyreidfayvfuwqa7qlnopdjiqrxzs6blmoeu4rujcjtnci5beludirz2a";
  @TempDir Path root;
  private final TestClock clock = new TestClock();
  private SqliteAutonomousDraftStore store;
  private AutonomousDraft initial;

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

  private SqliteAutonomousDraftStore reopened() {
    var d = AutonomousActionProperties.Bluesky.defaults();
    var settings =
        new AutonomousActionProperties(
            true,
            new AutonomousActionProperties.Bluesky(
                true,
                d.cron(),
                d.zone(),
                10,
                10,
                Duration.ofMillis(1),
                d.draftExpiration(),
                d.maxRegenerations(),
                d.regenerationCooldown(),
                d.systemPrompt()));
    return new SqliteAutonomousDraftStore(new StorageDatabase(root), clock, settings);
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    store = reopened();
    var claim =
        store
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial =
        store.complete(claim, "公開確認の本文", "PRIVATE_REASON", List.of("topic:one:1")).orElseThrow();
  }

  private AutonomousDraftNotice open() {
    return new AutonomousDraftNotice(initial.id(), true, initial.createdAt());
  }

  private AutonomousDraftNotice closed() {
    return new AutonomousDraftNotice(initial.id(), false, initial.createdAt());
  }

  @Test
  void completingADraftPersistsAnUndeliveredReferenceAcrossConnections() throws Exception {
    assertThat(store.pendingNotices(20)).containsExactly(open());
    assertThat(reopened().pendingNotices(20)).containsExactly(open());
    assertThat(store.noticeDelivered(open())).isTrue();
    assertThat(reopened().pendingNotices(20)).isEmpty();
    assertThat(store.noticeDelivered(open())).isFalse();
    assertThat(store.get(initial.id())).isEqualTo(initial);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var q = db.createStatement();
        var rows =
            q.executeQuery("SELECT pending,delivered_at FROM autonomous_notification_outbox")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt(1)).isZero();
      assertThat(rows.getLong(2)).isEqualTo(clock.instant().toEpochMilli());
      assertThat(rows.next()).isFalse();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"rejected", "expired", "publishing", "unknown", "published", "failed"})
  void leavingReviewQueuesTheSameItemForResolution(String transition) {
    assertThat(store.noticeDelivered(open())).isTrue();
    if (transition.equals("rejected")) store.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    else if (transition.equals("expired")) clock.now = clock.now.plus(Duration.ofDays(4));
    else {
      var claim = store.beginPublication(initial.id(), 1, "fixture");
      switch (transition) {
        case "unknown" ->
            store.finishPublication(
                claim, AutonomousPublicationStore.Outcome.UNKNOWN, null, null, "response_lost");
        case "published" ->
            store.finishPublication(
                claim, AutonomousPublicationStore.Outcome.PUBLISHED, claim.record().uri(), CID, "");
        case "failed" ->
            store.finishPublication(
                claim,
                AutonomousPublicationStore.Outcome.FAILED,
                null,
                null,
                "authentication_failed");
        default -> {}
      }
    }
    assertThat(store.pendingNotices(20)).containsExactly(closed());
    assertThat(closed().notificationId()).isEqualTo(open().notificationId());
    assertThat(store.noticeDelivered(open())).isFalse();
    assertThat(store.noticeDelivered(closed())).isTrue();
    assertThat(store.pendingNotices(20)).isEmpty();
    assertThat(store.get(initial.id()).state()).isNotEqualTo(AutonomousDraft.State.PENDING_REVIEW);
  }

  @Test
  void anOldDeliveryCannotConsumeALaterResolution() {
    var oldDelivery = store.pendingNotices(20).getFirst();
    store.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    assertThat(store.noticeDelivered(oldDelivery)).isFalse();
    assertThat(store.pendingNotices(20)).containsExactly(closed());
    assertThat(store.noticeDelivered(closed())).isTrue();
    assertThat(store.noticeDelivered(oldDelivery)).isFalse();
    assertThat(store.pendingNotices(20)).isEmpty();
  }

  @Test
  void editingAndRegenerationDoNotRearmAnAlreadyDeliveredReviewNotice() {
    assertThat(store.noticeDelivered(open())).isTrue();
    var edited = store.edit(initial.id(), 1, "編集した本文");
    assertThat(store.pendingNotices(20)).isEmpty();
    var regen =
        store
            .reserveRegeneration(
                "regenerate",
                initial.id(),
                edited.content().revision(),
                initial.target().did(),
                initial.target().handle())
            .claim();
    assertThat(regen).isNotNull();
    assertThat(store.completeRegeneration(regen, "再生成した本文", "PRIVATE_NEW_REASON", List.of()))
        .isPresent();
    assertThat(store.pendingNotices(20)).isEmpty();
  }

  @Test
  void startupRecoveryReplaysStableIntentWithoutDuplicatingRows() {
    assertThat(store.noticeDelivered(open())).isTrue();
    reopened().recoverNotices();
    assertThat(store.pendingNotices(20)).containsExactly(open());
    assertThat(store.noticeDelivered(open())).isTrue();
    store.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    assertThat(store.noticeDelivered(closed())).isTrue();
    reopened().recoverNotices();
    assertThat(store.pendingNotices(20)).containsExactly(closed());
    assertThat(store.noticeDelivered(closed())).isTrue();
    assertThat(store.pendingNotices(20)).isEmpty();
  }

  @Test
  void startupRecoveryClosesAnAlreadyDeliveredNoticeWhoseReviewDeadlinePassed() {
    assertThat(store.noticeDelivered(open())).isTrue();
    clock.now = clock.now.plus(Duration.ofDays(4));
    reopened().recoverNotices();
    assertThat(store.pendingNotices(1)).containsExactly(closed());
    assertThat(store.noticeDelivered(closed())).isTrue();
    assertThat(store.pendingNotices(1)).isEmpty();
  }

  @Test
  @org.junit.jupiter.api.Timeout(60)
  void recoveryVisitsHistoryBeyondOneBatchAndDeliveryRemainsBounded() {
    for (int index = 1; index < 300; index++) {
      clock.now = clock.now.plus(Duration.ofDays(1));
      var claim =
          store
              .reserve(
                  "history-" + index,
                  AutonomousDraft.Trigger.MANUAL,
                  "did:plc:example",
                  "rei.example")
              .claim();
      assertThat(store.complete(claim, "履歴の本文 " + index, "PRIVATE_REASON", List.of())).isPresent();
    }
    for (int replay = 0; replay < 2; replay++) {
      var seen = new java.util.HashSet<String>();
      List<AutonomousDraftNotice> batch;
      while (!(batch = store.pendingNotices(37)).isEmpty()) {
        assertThat(batch.size()).isLessThanOrEqualTo(37);
        for (var notice : batch) {
          assertThat(seen.add(notice.notificationId())).isTrue();
          assertThat(store.noticeDelivered(notice)).isTrue();
        }
      }
      assertThat(seen).hasSize(300);
      if (replay == 0) reopened().recoverNotices();
    }
  }

  @Test
  void malformedOrForeignAcknowledgmentsAndInvalidPageSizesDoNotChangeIntent() {
    assertThatThrownBy(() -> store.pendingNotices(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.pendingNotices(201))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.noticeDelivered(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.noticeDelivered(new AutonomousDraftNotice("missing", true, clock.instant())))
        .isFalse();
    assertThat(store.pendingNotices(20)).containsExactly(open());
  }

  @Test
  void twoDispatchersShareOneDurableDeliveryResult() {
    var other = reopened();
    assertThat(other.pendingNotices(20)).containsExactly(open());
    assertThat(store.noticeDelivered(open())).isTrue();
    assertThat(other.noticeDelivered(open())).isFalse();
    assertThat(other.pendingNotices(20)).isEmpty();
  }

  @Test
  void aFailedResolutionWriteRollsBackReviewAndPublicationChanges() throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var q = db.createStatement()) {
      q.execute(
          "CREATE TRIGGER fail_notice BEFORE UPDATE ON autonomous_notification_outbox WHEN NEW.review_required=0 BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
      assertThatThrownBy(() -> store.reject(initial.id(), 1, "PRIVATE_FEEDBACK"))
          .isInstanceOf(RuntimeException.class);
      assertThat(store.get(initial.id())).isEqualTo(initial);
      assertThatThrownBy(() -> store.beginPublication(initial.id(), 1, "fixture"))
          .isInstanceOf(RuntimeException.class);
      assertThat(store.get(initial.id())).isEqualTo(initial);
      assertThat(store.publicationRecord(initial.id())).isEmpty();
      assertThat(store.pendingNotices(20)).containsExactly(open());
    }
  }

  @Test
  void aFailedNoticeInsertCannotLeaveADraftWithoutItsNotificationIntent() throws Exception {
    store.reject(initial.id(), 1, "PRIVATE_FEEDBACK");
    clock.now = clock.now.plusMillis(2);
    var claim =
        store
            .reserve("next", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db"));
        var q = db.createStatement()) {
      q.execute(
          "CREATE TRIGGER fail_new_notice BEFORE INSERT ON autonomous_notification_outbox BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
      assertThatThrownBy(() -> store.complete(claim, "次の本文", "PRIVATE_REASON", List.of()))
          .isInstanceOf(RuntimeException.class);
      try (var rows = q.executeQuery("SELECT COUNT(*) FROM autonomous_drafts")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isEqualTo(1);
      }
      q.execute("DROP TRIGGER fail_new_notice");
    }
    assertThat(store.complete(claim, "次の本文", "PRIVATE_REASON", List.of())).isPresent();
    assertThat(store.pendingNotices(20)).hasSize(2);
  }

  @Test
  void shutdownDoesNotStartNotificationReadsOrRecoveryWork() {
    store.stop();
    assertThat(store.pendingNotices(20)).isEmpty();
    store.recoverNotices();
    assertThat(store.noticeDelivered(open())).isFalse();
    assertThat(reopened().pendingNotices(20)).containsExactly(open());
  }
}
