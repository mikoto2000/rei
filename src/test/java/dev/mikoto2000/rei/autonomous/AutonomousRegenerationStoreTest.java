package dev.mikoto2000.rei.autonomous;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
class AutonomousRegenerationStoreTest {
  @TempDir Path root;
  private final MutableClock clock = new MutableClock();
  private SqliteAutonomousDraftStore store;
  private AutonomousDraft initial;

  private AutonomousActionProperties settings(int regenerations) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        true,
        new AutonomousActionProperties.Bluesky(
            true,
            d.cron(),
            d.zone(),
            1,
            1,
            d.cooldown(),
            d.draftExpiration(),
            regenerations,
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  private SqliteAutonomousDraftStore reopened(int regenerations) {
    return new SqliteAutonomousDraftStore(
        new StorageDatabase(root), clock, settings(regenerations));
  }

  @BeforeEach
  void prepare() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    store = reopened(3);
    var claim =
        store
            .reserve("initial", AutonomousDraft.Trigger.MANUAL, "did:plc:example", "rei.example")
            .claim();
    initial = store.complete(claim, "初期の投稿案", "初期の理由", List.of("topic-1")).orElseThrow();
  }

  private AutonomousDraftStore.Consideration reserve(String opportunity, int revision) {
    return store.reserveRegeneration(
        opportunity, initial.id(), revision, initial.target().did(), initial.target().handle());
  }

  @Test
  void reservationSpendsAnAttemptAndCompletionCreatesAnUnapprovedRevision() {
    var claim = reserve("regen-1", 1).claim();
    var reserved = store.get(initial.id());
    assertThat(reserved.regenerationAttempts()).isEqualTo(1);
    assertThat(reserved.lastRegenerationAttemptAt()).isEqualTo(clock.instant());
    assertThat(reserved.content()).isEqualTo(initial.content());
    var completed =
        store.completeRegeneration(claim, "再生成した本文", "新しい理由", List.of("topic-2")).orElseThrow();
    assertThat(completed.id()).isEqualTo(initial.id());
    assertThat(completed.content().revision()).isEqualTo(2);
    assertThat(completed.content().text()).isEqualTo("再生成した本文");
    assertThat(completed.content().bodyHash()).isNotEqualTo(initial.content().bodyHash());
    assertThat(completed.createdAt()).isEqualTo(initial.createdAt());
    assertThat(completed.expiresAt()).isEqualTo(initial.expiresAt());
    assertThat(completed.target()).isEqualTo(initial.target());
    assertThat(completed.state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
    assertThat(completed.approval()).isNull();
    assertThat(completed.publication()).isNull();
    assertThat(store.list(20)).containsExactly(completed);
    assertThat(store.revisions(initial.id()))
        .containsExactly(initial.content(), completed.content());
    assertThatThrownBy(() -> store.completeRegeneration(claim, "再送", "理由", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
  }

  @Test
  void skipFailureAndSuccessSpendTheSameDurableLifetimeQuota() {
    var first = reserve("skip", 1).claim();
    store.skip(first, "今回は見送る");
    assertThat(store.get(initial.id()).content()).isEqualTo(initial.content());
    assertThat(reserve("too-soon", 1).decision().reason()).isEqualTo("regeneration_cooldown");
    clock.advance(Duration.ofHours(1));
    store.fail(reserve("failed", 1).claim());
    assertThat(store.get(initial.id()).content()).isEqualTo(initial.content());
    clock.advance(Duration.ofHours(1));
    var last =
        store
            .completeRegeneration(reserve("last", 1).claim(), "最後の再生成", "理由", List.of())
            .orElseThrow();
    assertThat(last.regenerationAttempts()).isEqualTo(3);
    clock.advance(Duration.ofHours(1));
    assertThat(reserve("over-limit", 2).decision().reason()).isEqualTo("regeneration_limit");
    var restarted = reopened(3);
    assertThat(
            restarted
                .reserveRegeneration(
                    "after-restart",
                    initial.id(),
                    2,
                    initial.target().did(),
                    initial.target().handle())
                .decision()
                .reason())
        .isEqualTo("regeneration_limit");
    new StorageDatabase(root)
        .read(db -> {
          try (var sql =
              db.prepareStatement(
                  "SELECT COUNT(*) FROM autonomous_opportunities WHERE kind='REGENERATE' AND considered=1 AND draft_id=?")) {
            sql.setString(1, initial.id());
            try (var rows = sql.executeQuery()) {
              assertThat(rows.next()).isTrue();
              assertThat(rows.getInt(1)).isEqualTo(3);
            }
          }
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"edit", "reject", "expire", "stop"})
  void changesWhileGeneratingAbortCompletionWithoutOverwriting(String change) {
    var claim = reserve("in-flight", 1).claim();
    switch (change) {
      case "edit" -> store.edit(initial.id(), 1, "人間が編集した本文");
      case "reject" -> store.reject(initial.id(), 1, "非公開の理由");
      case "expire" -> clock.advance(Duration.ofHours(72));
      case "stop" -> store.stop();
      default -> throw new AssertionError(change);
    }
    assertThat(store.completeRegeneration(claim, "破棄する生成結果", "理由", List.of())).isEmpty();
    var kept = store.get(initial.id());
    assertThat(kept.content().text())
        .isEqualTo(change.equals("edit") ? "人間が編集した本文" : initial.content().text());
    assertThat(kept.content().revision()).isEqualTo(change.equals("edit") ? 2 : 1);
    assertThat(kept.expiresAt()).isEqualTo(initial.expiresAt());
    assertThat(kept.regenerationAttempts()).isEqualTo(1);
    assertThat(kept.state())
        .isEqualTo(
            switch (change) {
              case "reject" -> AutonomousDraft.State.REJECTED;
              case "expire" -> AutonomousDraft.State.EXPIRED;
              default -> AutonomousDraft.State.PENDING_REVIEW;
            });
    assertThat(kept.approval()).isNull();
    assertThat(kept.publication()).isNull();
    new StorageDatabase(root)
        .read(db -> {
          try (var sql = db.createStatement();
              var rows =
                  sql.executeQuery(
                      "SELECT status,draft_id FROM autonomous_opportunities WHERE id='in-flight'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("ABORTED");
            assertThat(rows.getString(2)).isEqualTo(initial.id());
          }
          return null;
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"reject", "expire"})
  void terminalDraftCannotReserveRegeneration(String terminal) {
    if (terminal.equals("reject")) store.reject(initial.id(), 1, "");
    else clock.advance(Duration.ofHours(72));
    assertThatThrownBy(() -> reserve("terminal", 1))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(initial.id()).regenerationAttempts()).isZero();
  }

  @Test
  void repeatedOpportunityDoesNotConsumeAdditionalAttempts() {
    var claim = reserve("same-opportunity", 1).claim();
    assertThat(reserve("same-opportunity", 1).decision().reason())
        .isEqualTo("duplicate_opportunity");
    store.completeRegeneration(claim, "再生成した本文", "理由", List.of()).orElseThrow();
    assertThat(reserve("same-opportunity", 2).decision().reason())
        .isEqualTo("duplicate_opportunity");
    assertThat(store.get(initial.id()).regenerationAttempts()).isEqualTo(1);
  }

  @Test
  void staleRevisionCannotReserveOrSpendAnAttempt() {
    store.edit(initial.id(), 1, "変更済み");
    assertThatThrownBy(() -> reserve("stale", 1)).isInstanceOf(AutonomousConflictException.class);
    assertThat(store.get(initial.id()).regenerationAttempts()).isZero();
  }

  @Test
  void zeroConfiguredLimitIsAppliedByTheRepository() {
    assertThat(
            reopened(0)
                .reserveRegeneration(
                    "disabled-regen",
                    initial.id(),
                    1,
                    initial.target().did(),
                    initial.target().handle())
                .decision()
                .reason())
        .isEqualTo("regeneration_limit");
    assertThat(store.get(initial.id()).regenerationAttempts()).isZero();
  }

  @Test
  void changingConfiguredAccountDoesNotRetargetTheDraft() {
    assertThat(
            store
                .reserveRegeneration(
                    "other-account", initial.id(), 1, "did:plc:other", "other.example")
                .decision()
                .reason())
        .isEqualTo("target_changed");
    assertThat(store.get(initial.id()).target()).isEqualTo(initial.target());
    assertThat(store.get(initial.id()).regenerationAttempts()).isZero();
    assertThat(reserve("correct-account", 1).decision().shouldConsider()).isTrue();
  }

  @Test
  void newDraftAndRegenerationClaimsAreNotInterchangeable() {
    var regeneration = reserve("regen-kind", 1).claim();
    assertThatThrownBy(() -> store.complete(regeneration, "別の投稿案", "理由", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
    var edited = store.completeRegeneration(regeneration, "再生成", "理由", List.of()).orElseThrow();
    store.reject(initial.id(), edited.content().revision(), "");
    clock.advance(Duration.ofDays(1));
    var fresh =
        store
            .reserve(
                "new-kind",
                AutonomousDraft.Trigger.MANUAL,
                initial.target().did(),
                initial.target().handle())
            .claim();
    assertThatThrownBy(() -> store.completeRegeneration(fresh, "誤った再生成", "理由", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(store.complete(fresh, "新規の本文", "理由", List.of())).isPresent();
  }

  @Test
  void recoveryKeepsTheOldBodyAndConsumedAttempt() {
    var interrupted = reserve("interrupted", 1).claim();
    var restarted = reopened(3);
    restarted.recoverInterruptedConsiderations();
    assertThat(restarted.get(initial.id()).content()).isEqualTo(initial.content());
    assertThat(restarted.get(initial.id()).regenerationAttempts()).isEqualTo(1);
    assertThatThrownBy(() -> restarted.completeRegeneration(interrupted, "遅れた結果", "理由", List.of()))
        .isInstanceOf(AutonomousConflictException.class);
    assertThat(
            restarted
                .reserveRegeneration(
                    "recovered", initial.id(), 1, initial.target().did(), initial.target().handle())
                .decision()
                .reason())
        .isEqualTo("regeneration_cooldown");
  }

  @Test
  void twoRepositoriesCanReserveOnlyOneConcurrentAttempt() throws Exception {
    var other = reopened(3);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> {
            ready.countDown();
            start.await();
            return reserve("parallel-1", 1);
          });
      var second =
          executor.submit(() -> {
            ready.countDown();
            start.await();
            return other.reserveRegeneration(
                "parallel-2", initial.id(), 1, initial.target().did(), initial.target().handle());
          });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      var results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertThat(results.stream().filter(r -> r.decision().shouldConsider()).count()).isEqualTo(1);
      assertThat(store.get(initial.id()).regenerationAttempts()).isEqualTo(1);
    }
  }

  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-28T03:00:00Z");

    void advance(Duration elapsed) {
      now = now.plus(elapsed);
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Tokyo");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
