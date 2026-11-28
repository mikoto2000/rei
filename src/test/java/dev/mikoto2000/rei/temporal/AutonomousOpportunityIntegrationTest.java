package dev.mikoto2000.rei.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.autonomous.AutonomousDraft;
import dev.mikoto2000.rei.autonomous.AutonomousGenerationService;
import dev.mikoto2000.rei.autonomous.AutonomousPublicSources;
import dev.mikoto2000.rei.storage.SqliteAutonomousDraftStore;
import dev.mikoto2000.rei.storage.SqliteAutonomousPublicSources;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("integration")
@Timeout(60)
class AutonomousOpportunityIntegrationTest {
  @TempDir Path root;

  private AutonomousActionProperties settings(
      int daily, Duration cooldown, String zone, boolean scheduled) {
    return settings(daily, cooldown, zone, scheduled, "0 * * * * *");
  }

  private AutonomousActionProperties settings(
      int daily, Duration cooldown, String zone, boolean scheduled, String cron) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        true,
        new AutonomousActionProperties.Bluesky(
            true,
            scheduled,
            cron,
            zone,
            daily,
            1,
            cooldown,
            d.draftExpiration(),
            d.maxRegenerations(),
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  @Test
  void scheduledConsiderationEndsInAnUnapprovedDraftAndDurableReviewNotice() throws Exception {
    try (var f = new Fixture(settings(1, Duration.ofHours(1), "", true), true);
        var scheduled = f.scheduler()) {
      var result = scheduled.at("2026-11-28T12:00:00Z");
      assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
      var draft = result.draft();
      assertThat(draft.trigger()).isEqualTo(AutonomousDraft.Trigger.SCHEDULED);
      assertThat(draft.opportunityId()).isEqualTo("scheduled:bluesky:2026-11-28T12:00:00Z");
      assertThat(draft.state()).isEqualTo(AutonomousDraft.State.PENDING_REVIEW);
      assertThat(f.drafts.pendingNotices(20))
          .filteredOn(n -> n.reviewRequired())
          .extracting(n -> n.draftId())
          .containsExactly(draft.id());
      assertThat(f.models.get()).isEqualTo(1);
      f.assertUnpublished();
    }
  }

  @Test
  void twoDispatchersPersistTheSameOpportunityOnlyOnce() throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofMillis(1), "", true), true);
        var first = f.scheduler();
        var second = f.scheduler()) {
      assertThat(first.at("2026-11-28T12:00:00Z").status())
          .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
      var duplicate = second.at("2026-11-28T12:00:00Z");
      assertThat(duplicate.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
      assertThat(duplicate.reason()).isEqualTo("duplicate_opportunity");
      assertThat(f.count("autonomous_opportunities")).isEqualTo(1);
      assertThat(f.drafts.list(20)).hasSize(1);
      assertThat(f.models.get()).isEqualTo(1);
      f.assertUnpublished();
    }
  }

  @Test
  void manualAndScheduledGenerationSharePendingCooldownAndDailyLimits() throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofHours(1), "", true), true);
        var scheduled = f.scheduler()) {
      var manual = f.generation.manual().draft();
      assertThat(manual.trigger()).isEqualTo(AutonomousDraft.Trigger.MANUAL);
      assertThat(scheduled.at("2026-11-28T12:00:00Z").reason()).isEqualTo("pending_limit");
      f.drafts.reject(manual.id(), 1, "PRIVATE_REJECTION");
      assertThat(scheduled.at("2026-11-28T12:01:00Z").reason()).isEqualTo("cooldown");
      var second = scheduled.at("2026-11-28T13:00:00Z").draft();
      f.drafts.reject(second.id(), 1, "PRIVATE_REJECTION");
      var third = scheduled.at("2026-11-28T14:00:00Z").draft();
      f.drafts.reject(third.id(), 1, "PRIVATE_REJECTION");
      assertThat(scheduled.at("2026-11-28T15:00:00Z").reason()).isEqualTo("daily_limit");
      assertThat(f.models.get()).isEqualTo(3);
      assertThat(f.count("autonomous_drafts")).isEqualTo(3);
      assertThat(f.lastSources.get().toString()).doesNotContain("PRIVATE_REJECTION");
      f.assertUnpublished();
    }
  }

  @Test
  void theDailyLimitSurvivesACompleteRepositoryAndServiceRestart() throws Exception {
    try (var f = new Fixture(settings(1, Duration.ofMillis(1), "", true), true)) {
      var draft = f.generation.manual().draft();
      f.drafts.reject(draft.id(), 1, "今回は見送る");
      f.clock.at("2026-11-28T12:00:00Z");
      f.reopen();
      try (var scheduled = f.scheduler()) {
        assertThat(scheduled.at("2026-11-28T12:01:00Z").reason()).isEqualTo("daily_limit");
        assertThat(f.models.get()).isEqualTo(1);
        assertThat(f.count("autonomous_drafts")).isEqualTo(1);
        f.assertUnpublished();
      }
    }
  }

  @Test
  void cooldownSurvivesRestartAndLaterAllowsTheNextValidOccurrence() throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofHours(1), "", true), true)) {
      var draft = f.generation.manual().draft();
      f.drafts.reject(draft.id(), 1, "今回は見送る");
      f.clock.at("2026-11-28T12:00:00Z");
      f.reopen();
      try (var scheduled = f.scheduler()) {
        assertThat(scheduled.at("2026-11-28T12:01:00Z").reason()).isEqualTo("cooldown");
        assertThat(scheduled.at("2026-11-28T13:00:00Z").status())
            .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
        assertThat(f.models.get()).isEqualTo(2);
        f.assertUnpublished();
      }
    }
  }

  @Test
  void quotaResetsAtTheConfiguredLocalDateBoundaryRatherThanUtcMidnight() throws Exception {
    try (var f = new Fixture(settings(1, Duration.ofMillis(1), "Asia/Tokyo", true), true)) {
      f.clock.at("2026-11-28T14:58:59Z");
      try (var scheduled = f.scheduler()) {
        var beforeMidnight = scheduled.at("2026-11-28T14:59:00Z").draft();
        f.drafts.reject(beforeMidnight.id(), 1, "今回は見送る");
        f.clock.at("2026-11-28T14:59:30Z");
        assertThat(f.generation.manual().reason()).isEqualTo("daily_limit");
        var afterMidnight = scheduled.at("2026-11-28T15:00:00Z");
        assertThat(afterMidnight.status())
            .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
        assertThat(afterMidnight.draft().id()).isNotEqualTo(beforeMidnight.id());
        assertThat(f.models.get()).isEqualTo(2);
        f.assertUnpublished();
      }
    }
  }

  @Test
  void startupAbandonsACrashedClaimWithoutResumingOrReplayingItsOldSlot() throws Exception {
    try (var f = new Fixture(settings(1, Duration.ofMillis(1), "", true, "0 0 12 * * *"), true)) {
      f.clock.at("2026-11-28T12:00:00Z");
      var admission =
          f.drafts.reserve(
              "scheduled:bluesky:2026-11-28T12:00:00Z",
              AutonomousDraft.Trigger.SCHEDULED,
              "did:plc:example",
              "rei.example");
      assertThat(admission.decision().shouldConsider()).isTrue();
      f.reopen();
      assertThat(f.running()).isZero();
      try (var scheduled = f.scheduler()) {
        scheduled.tickAt("2026-11-28T12:00:00Z");
        scheduled.tickAt("2026-11-28T12:00:30Z");
        assertThat(scheduled.results).isEmpty();
        assertThat(f.models.get()).isZero();
        assertThat(scheduled.at("2026-11-29T12:00:00Z").status())
            .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
        assertThat(f.models.get()).isEqualTo(1);
        assertThat(f.count("autonomous_opportunities")).isEqualTo(2);
        f.assertUnpublished();
      }
    }
  }

  @Test
  void periodicDisableStillAllowsManualGenerationAndExplicitRegeneration() throws Exception {
    try (var f = new Fixture(settings(1, Duration.ofHours(1), "", false), true);
        var scheduled = f.scheduler()) {
      scheduled.tickAt("2026-11-28T12:00:00Z");
      assertThat(scheduled.results).isEmpty();
      assertThat(f.count("autonomous_opportunities")).isZero();
      var manual = f.generation.manual();
      assertThat(manual.status()).isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
      var regenerated = f.generation.regenerate(manual.draftId(), 1);
      assertThat(regenerated.status())
          .isEqualTo(AutonomousGenerationService.Status.DRAFT_REGENERATED);
      assertThat(regenerated.draftId()).isEqualTo(manual.draftId());
      assertThat(regenerated.draft().content().revision()).isEqualTo(2);
      assertThat(f.count("autonomous_drafts")).isEqualTo(1);
      assertThat(f.models.get()).isEqualTo(2);
      f.assertUnpublished();
    }
  }

  @Test
  void unavailableTargetDoesNotReachTheModelOrCreateADraft() throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofHours(1), "", true), true);
        var scheduled = f.scheduler()) {
      f.target.set(null);
      var result = scheduled.at("2026-11-28T12:00:00Z");
      assertThat(result.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
      assertThat(result.reason()).isEqualTo("target_not_configured");
      assertThat(f.models.get()).isZero();
      assertThat(f.drafts.list(20)).isEmpty();
      f.assertUnpublished();
    }
  }

  @Test
  void noSourcesAndModelSkipAreNormalOutcomesAndFailureDoesNotCauseAnImmediateRetry()
      throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofHours(1), "", true), false);
        var scheduled = f.scheduler()) {
      assertThat(scheduled.at("2026-11-28T12:00:00Z").reason()).isEqualTo("no_public_sources");
      assertThat(f.models.get()).isZero();
      f.sources.permission(f.topic.id(), 1, true, "fixture");
      f.mode.set(Mode.SKIP);
      assertThat(scheduled.at("2026-11-28T13:00:00Z").status())
          .isEqualTo(AutonomousGenerationService.Status.SKIPPED);
      f.mode.set(Mode.FAIL);
      var failed = scheduled.at("2026-11-28T14:00:00Z");
      assertThat(failed.status()).isEqualTo(AutonomousGenerationService.Status.FAILED);
      assertThat(failed.reason()).isEqualTo("generation_failed");
      assertThat(scheduled.at("2026-11-28T14:01:00Z").reason()).isEqualTo("cooldown");
      assertThat(f.models.get()).isEqualTo(2);
      assertThat(f.drafts.list(20)).isEmpty();
      f.mode.set(Mode.PROPOSE);
      assertThat(scheduled.at("2026-11-28T15:00:00Z").status())
          .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
      assertThat(f.models.get()).isEqualTo(3);
      assertThat(f.reasons().toString()).doesNotContain("PRIVATE_PROVIDER_TEXT");
      f.assertUnpublished();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void manualAndScheduledRacesHaveOneSharedDurableClaim(boolean manualFirst) throws Exception {
    try (var f = new Fixture(settings(3, Duration.ofMillis(1), "", true), true);
        var scheduled = f.scheduler()) {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      f.beforeModel =
          () -> {
            started.countDown();
            try {
              if (!release.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Fixture did not release the model");
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new CancellationException("fixture cancellation");
            }
          };
      var caller = Executors.newSingleThreadExecutor();
      try {
        f.clock.at("2026-11-28T12:00:00Z");
        var first =
            caller.submit(
                () -> manualFirst ? f.generation.manual() : scheduled.at("2026-11-28T12:00:00Z"));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        var second = manualFirst ? scheduled.at("2026-11-28T12:00:00Z") : f.generation.manual();
        assertThat(second.status()).isEqualTo(AutonomousGenerationService.Status.SKIPPED);
        assertThat(second.reason()).isEqualTo("active_opportunity");
        assertThat(f.models.get()).isEqualTo(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).status())
            .isEqualTo(AutonomousGenerationService.Status.DRAFT_CREATED);
        assertThat(f.drafts.list(20)).hasSize(1);
        assertThat(f.running()).isZero();
        f.assertUnpublished();
      } finally {
        release.countDown();
        caller.shutdownNow();
        assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private enum Mode {
    PROPOSE,
    SKIP,
    FAIL
  }

  private final class Fixture implements AutoCloseable {
    final MutableClock clock = new MutableClock();
    final AutonomousActionProperties settings;
    final StorageDatabase database;
    final SqliteAutonomousPublicSources sources;
    final AutonomousPublicSources.Source topic;
    final AtomicInteger models = new AtomicInteger();
    final AtomicReference<Mode> mode = new AtomicReference<>(Mode.PROPOSE);
    final AtomicReference<AutonomousDraft.Target> target =
        new AtomicReference<>(new AutonomousDraft.Target("did:plc:example", "rei.example"));
    final AtomicReference<List<AutonomousPublicSources.PublicSource>> lastSources =
        new AtomicReference<>();
    final List<SqliteAutonomousDraftStore> stores = new ArrayList<>();
    volatile Runnable beforeModel = () -> {};
    SqliteAutonomousDraftStore drafts;
    AutonomousGenerationService generation;

    Fixture(AutonomousActionProperties settings, boolean permitted) throws Exception {
      this.settings = settings;
      try (var migration = new StorageMigrationCoordinator(root)) {
        migration.prepare();
      }
      database = new StorageDatabase(root);
      sources = new SqliteAutonomousPublicSources(database, clock);
      topic = sources.register("公開用に登録した話題", permitted, "fixture");
      reopen();
    }

    void reopen() {
      if (drafts != null) drafts.stop();
      drafts = new SqliteAutonomousDraftStore(database, clock, settings);
      stores.add(drafts);
      drafts.recoverInterruptedConsiderations();
      drafts.recoverNotices();
      generation =
          new AutonomousGenerationService(
              drafts,
              sources,
              target::get,
              snapshot -> {
                int attempt = models.incrementAndGet();
                lastSources.set(List.copyOf(snapshot));
                beforeModel.run();
                if (mode.get() == Mode.FAIL)
                  throw new IllegalStateException("PRIVATE_PROVIDER_TEXT");
                if (mode.get() == Mode.SKIP)
                  return "{\"decision\":\"SKIP\",\"reason\":\"no_story\"}";
                var json = StorageDatabase.JSON.createObjectNode();
                json.put("decision", "PROPOSE_POST").put("reason", "公開用の話題を共有");
                var body = json.putObject("draft");
                body.put("text", "生成した投稿案-" + attempt);
                body.putArray("sourceIds").add(snapshot.getFirst().id());
                return json.toString();
              });
    }

    Scheduled scheduler() {
      return new Scheduled(this);
    }

    int count(String table) {
      return database.read(db -> {
        try (var sql = db.createStatement();
            var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
          rows.next();
          return rows.getInt(1);
        }
      });
    }

    int running() {
      return database.read(db -> {
        try (var sql = db.createStatement();
            var rows =
                sql.executeQuery(
                    "SELECT COUNT(*) FROM autonomous_opportunities WHERE status='RUNNING'")) {
          rows.next();
          return rows.getInt(1);
        }
      });
    }

    List<String> reasons() {
      return database.read(db -> {
        var result = new ArrayList<String>();
        try (var sql = db.createStatement();
            var rows = sql.executeQuery("SELECT reason FROM autonomous_opportunities")) {
          while (rows.next()) result.add(rows.getString(1));
        }
        return result;
      });
    }

    void assertUnpublished() {
      assertThat(count("autonomous_publications")).isZero();
      assertThat(drafts.list(200))
          .allSatisfy(draft -> {
            assertThat(draft.approval()).isNull();
            assertThat(draft.publication()).isNull();
          });
    }

    public void close() {
      stores.forEach(SqliteAutonomousDraftStore::stop);
    }
  }

  private static final class Scheduled implements AutoCloseable {
    final List<AutonomousGenerationService.Result> results = new CopyOnWriteArrayList<>();
    final MutableClock clock;
    final AutonomousOpportunityScheduler scheduler;

    Scheduled(Fixture f) {
      clock = f.clock;
      var store = f.drafts;
      var generation = f.generation;
      scheduler =
          new AutonomousOpportunityScheduler(
              f.settings,
              clock,
              store::isStopping,
              id -> results.add(generation.consider(id, AutonomousDraft.Trigger.SCHEDULED)),
              new InlineWorker());
    }

    void tickAt(String instant) {
      clock.at(instant);
      scheduler.tick();
    }

    AutonomousGenerationService.Result at(String instant) {
      tickAt(instant);
      assertThat(results).isNotEmpty();
      return results.getLast();
    }

    public void close() {
      scheduler.close();
    }
  }

  private static final class InlineWorker extends AbstractExecutorService {
    private boolean closed;

    public void execute(Runnable work) {
      if (closed) throw new RejectedExecutionException();
      work.run();
    }

    public void shutdown() {
      closed = true;
    }

    public List<Runnable> shutdownNow() {
      closed = true;
      return List.of();
    }

    public boolean isShutdown() {
      return closed;
    }

    public boolean isTerminated() {
      return closed;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return closed;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value =
        new AtomicReference<>(Instant.parse("2026-11-28T11:59:59Z"));

    void at(String instant) {
      value.set(Instant.parse(instant));
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(instant(), zone);
    }

    public Instant instant() {
      return value.get();
    }
  }
}
