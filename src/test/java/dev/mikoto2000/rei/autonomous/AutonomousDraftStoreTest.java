package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AutonomousDraftLifecycle;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class AutonomousDraftStoreTest {
  @TempDir Path root;
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-28T03:00:00Z"));

  private AutonomousActionProperties enabled() {
    var defaults = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        true,
        new AutonomousActionProperties.Bluesky(
            true,
            defaults.cron(),
            defaults.zone(),
            1,
            1,
            defaults.cooldown(),
            defaults.draftExpiration(),
            defaults.maxRegenerations(),
            defaults.regenerationCooldown(),
            defaults.systemPrompt()));
  }

  @Test
  void aConsideredOpportunityProducesAnUnapprovedPersistentDraft() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    var store =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    var opportunity =
        store.reserve("manual-1", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example");
    assertThat(opportunity.decision().shouldConsider()).isTrue();
    var saved = store.complete(opportunity.claim(), "公開する前に確認する本文", "共有したい話題", List.of("topic-1"));
    assertThat(saved).isPresent();
    assertThat(saved.orElseThrow().state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    assertThat(saved.orElseThrow().approval()).isNull();
    assertThat(saved.orElseThrow().publication()).isNull();
    assertThat(saved.orElseThrow().content().revision()).isEqualTo(1);
    var restored =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    assertThat(restored.get(saved.orElseThrow().id())).isEqualTo(saved.orElseThrow());
    assertThat(restored.list(20)).containsExactly(saved.orElseThrow());
  }

  private AutonomousDraftStore store() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    return new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
        new StorageDatabase(root), clock, enabled());
  }

  private AutonomousDraftStore.Consideration reserve(AutonomousDraftStore store, String id) {
    return store.reserve(id, AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example");
  }

  private AutonomousDraft draft(AutonomousDraftStore store) {
    var claim = reserve(store, UUID.randomUUID().toString()).claim();
    return store.complete(claim, "確認待ちの本文", "話題を共有したい", List.of("topic-1")).orElseThrow();
  }

  @Test
  void editingCreatesARevisionAndRejectsStaleRevisionWithoutApproving() throws Exception {
    var store = store();
    var saved = draft(store);
    var edited = store.edit(saved.id(), 1, "編集した新しい本文");
    assertThat(edited.content().revision()).isEqualTo(2);
    assertThat(edited.content().bodyHash()).isNotEqualTo(saved.content().bodyHash());
    assertThat(edited.approval()).isNull();
    assertThat(edited.publication()).isNull();
    assertThat(store.revisions(saved.id())).containsExactly(saved.content(), edited.content());
    assertThatThrownBy(() -> store.edit(saved.id(), 1, "古い画面からの編集"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(saved.id())).isEqualTo(edited);
  }

  @Test
  void rejectionIsTerminalAndReasonIsPrivateByDefault() throws Exception {
    var store = store();
    var saved = draft(store);
    var rejected = store.reject(saved.id(), 1, "非公開の見送り理由");
    assertThat(rejected.state()).isEqualTo(AutonomousDraft.State.REJECTED);
    assertThat(rejected.rejectionReason()).isEqualTo("非公開の見送り理由");
    assertThat(rejected.rejectionReasonPublic()).isFalse();
    assertThatThrownBy(() -> store.edit(saved.id(), 1, "編集不可"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThatThrownBy(() -> store.reject(saved.id(), 1, "重複見送り"))
        .isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void pendingAndDailyLimitsSurviveNewRepositoryInstances() throws Exception {
    var store = store();
    var saved = draft(store);
    var restarted =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    assertThat(reserve(restarted, "pending-block").decision().reason()).isEqualTo("pending_limit");
    store.reject(saved.id(), 1, "");
    clock.advance(Duration.ofHours(2));
    assertThat(reserve(restarted, "daily-block").decision().reason()).isEqualTo("daily_limit");
    clock.advance(Duration.ofDays(1));
    assertThat(reserve(restarted, "next-day").decision().shouldConsider()).isTrue();
  }

  @Test
  void skipIsNormalButDoesNotBypassDurableAttemptCooldown() throws Exception {
    var store = store();
    var consideration = reserve(store, "skip");
    store.skip(consideration.claim(), "今回は共有する話題がない");
    assertThat(store.list(20)).isEmpty();
    var restarted =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    clock.advance(Duration.ofMinutes(59));
    assertThat(reserve(restarted, "early").decision().reason()).isEqualTo("cooldown");
    clock.advance(Duration.ofMinutes(1));
    assertThat(reserve(restarted, "boundary").decision().shouldConsider()).isTrue();
    assertThatThrownBy(() -> store.complete(consideration.claim(), "再利用不可", "", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void shutdownCancelsReservedWorkWithoutSavingItsResult() throws Exception {
    var store = store();
    var claim = reserve(store, "in-flight").claim();
    store.stop();
    assertThat(store.complete(claim, "保存しない本文", "", List.of())).isEmpty();
    assertThat(reserve(store, "stopped").decision().reason()).isEqualTo("stopping");
    assertThat(store.list(20)).isEmpty();
  }

  @Test
  void restartAbortsOutstandingClaimsInsteadOfAutomaticallyResuming() throws Exception {
    var store = store();
    var claim = reserve(store, "old-run").claim();
    var restarted =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    restarted.recoverInterruptedConsiderations();
    assertThatThrownBy(() -> restarted.complete(claim, "再開しない", "", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(reserve(restarted, "old-run").decision().reason())
        .isEqualTo("duplicate_opportunity");
    assertThat(restarted.list(20)).isEmpty();
    clock.advance(Duration.ofHours(1));
    assertThat(reserve(restarted, "fresh-run").decision().shouldConsider()).isTrue();
  }

  @Test
  void expirationIsPersistentAndCannotBeEdited() throws Exception {
    var store = store();
    var saved = draft(store);
    clock.advance(Duration.ofHours(72));
    assertThat(store.get(saved.id()).state()).isEqualTo(AutonomousDraft.State.EXPIRED);
    assertThatThrownBy(() -> store.edit(saved.id(), 1, "期限切れ"))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(
            new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
                    new StorageDatabase(root), clock, enabled())
                .get(saved.id())
                .state())
        .isEqualTo(AutonomousDraft.State.EXPIRED);
  }

  @Test
  void concurrentManualAndScheduledRequestsReserveOnlyOneOpportunity() throws Exception {
    var first = store();
    var second =
        new dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore(
            new StorageDatabase(root), clock, enabled());
    var start = new CountDownLatch(1);
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var a =
          tasks.submit(() -> {
            start.await();
            return reserve(first, "manual");
          });
      var b =
          tasks.submit(() -> {
            start.await();
            return second.reserve(
                "scheduled", AutonomousDraft.Trigger.SCHEDULED, "did:plc:example", "rei.example");
          });
      start.countDown();
      var results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(r -> r.decision().shouldConsider()).count()).isEqualTo(1);
      assertThat(
              results.stream()
                  .filter(r -> !r.decision().shouldConsider())
                  .findFirst()
                  .orElseThrow()
                  .decision()
                  .reason())
          .isEqualTo("active_opportunity");
    }
  }

  @Test
  void duplicateOpportunityCannotCreateASecondDraft() throws Exception {
    var store = store();
    var claim = reserve(store, "same-id").claim();
    var saved = store.complete(claim, "一度だけ保存", "", List.of()).orElseThrow();
    assertThat(reserve(store, "same-id").decision().reason()).isEqualTo("duplicate_opportunity");
    assertThatThrownBy(() -> store.complete(claim, "二度目", "", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.list(20)).containsExactly(saved);
  }

  @Test
  void inconsistentStoredDestinationFailsClosed() throws Exception {
    var store = store();
    var saved = draft(store);
    new StorageDatabase(root)
        .transaction(db -> {
          try (var change =
              db.prepareStatement("UPDATE autonomous_drafts SET target_did=? WHERE id=?")) {
            change.setString(1, "did:plc:different");
            change.setString(2, saved.id());
            change.executeUpdate();
          }
          return null;
        });
    assertThatThrownBy(() -> store.get(saved.id())).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shutdownEventClosesAdmissionBeforeContextDestruction() throws Exception {
    var store = store();
    var bus = new dev.mikoto2000.rei.event.InMemoryAgentEventBus();
    try (var lifecycle = new AutonomousDraftLifecycle(store, bus)) {
      bus.publish(
          new dev.mikoto2000.rei.event.AgentEventFactory(clock)
              .applicationShutdownStarted("shell_exit"));
      assertThat(store.isStopping()).isTrue();
      assertThat(reserve(store, "after-shutdown-event").decision().reason()).isEqualTo("stopping");
    }
  }

  @Test
  void restartRecoveryLeavesAnAuditFactWithoutRegenerating() throws Exception {
    var store = store();
    reserve(store, "recover-audit");
    store.recoverInterruptedConsiderations();
    long recovered =
        new StorageDatabase(root)
            .read(db -> {
              try (var sql = db.createStatement();
                  var rows =
                      sql.executeQuery(
                          "SELECT COUNT(*) FROM autonomous_audit WHERE operation='RESTART_RECOVERY'")) {
                rows.next();
                return rows.getLong(1);
              }
            });
    assertThat(recovered).isEqualTo(1);
    assertThat(store.list(20)).isEmpty();
  }

  @Test
  void aBoundedListNeverDisplaysExpiredDraftsAsPendingBeyondTheMaintenanceBatch() throws Exception {
    var store = store();
    new StorageDatabase(root)
        .transaction(db -> {
          try (var opportunity =
                  db.prepareStatement(
                      "INSERT INTO autonomous_opportunities(id,trigger,kind,status,created,updated,considered,zone,target_did,target_handle,reason) VALUES(?,'MANUAL','NEW','GENERATED',0,0,1,'Asia/Tokyo','did:plc:example','rei.example','seed')");
              var draft =
                  db.prepareStatement(
                      "INSERT INTO autonomous_drafts VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            for (int index = 0; index < 260; index++) {
              String id = "seed-" + index;
              var created = clock.instant().plusMillis(index);
              var expires = created.plus(Duration.ofHours(72));
              var content = AutonomousDraft.Content.create(1, "期限付きの本文", "reason", List.of());
              var snapshot =
                  new AutonomousDraft(
                      id,
                      AutonomousDraft.ActionType.BLUESKY_POST,
                      id,
                      AutonomousDraft.Trigger.MANUAL,
                      created,
                      created,
                      new AutonomousDraft.Target("did:plc:example", "rei.example"),
                      content,
                      AutonomousDraft.State.PENDING_REVIEW,
                      expires,
                      "",
                      false,
                      0,
                      null,
                      null,
                      null);
              opportunity.setString(1, id);
              opportunity.executeUpdate();
              Object[] fields = {
                id,
                id,
                "PENDING_REVIEW",
                1,
                created.toEpochMilli(),
                created.toEpochMilli(),
                expires.toEpochMilli(),
                "did:plc:example",
                content.bodyHash(),
                StorageDatabase.JSON.writeValueAsString(snapshot)
              };
              for (int field = 0; field < fields.length; field++)
                draft.setObject(field + 1, fields[field]);
              draft.executeUpdate();
            }
          }
          return null;
        });
    clock.advance(Duration.ofHours(73));
    assertThat(store.list(200))
        .hasSize(200)
        .allMatch(draft -> draft.state() == AutonomousDraft.State.EXPIRED);
  }

  @Test
  void dailyLimitRollsOverAtInheritedZoneMidnightRatherThanUtcMidnight() throws Exception {
    clock.advance(Duration.ofHours(11));
    var store = store();
    var saved = draft(store);
    store.reject(saved.id(), 1, "");
    clock.advance(Duration.ofMinutes(59));
    assertThat(reserve(store, "before-tokyo-midnight").decision().reason())
        .isEqualTo("daily_limit");
    clock.advance(Duration.ofMinutes(1));
    assertThat(
            store
                .reserve(
                    "after-tokyo-midnight",
                    AutonomousDraft.Trigger.SCHEDULED,
                    "did:plc:example",
                    "rei.example")
                .decision()
                .shouldConsider())
        .isTrue();
  }

  private static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Tokyo");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant, zone);
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
