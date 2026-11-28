package dev.mikoto2000.rei.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AutonomousOpportunitySchedulerTest {
  private final MutableClock clock =
      new MutableClock(Instant.parse("2026-11-28T11:59:59Z"), ZoneOffset.UTC);
  private final ManualWorker worker = new ManualWorker();
  private final List<String> considered = new ArrayList<>();
  private final AtomicBoolean stopping = new AtomicBoolean();

  private AutonomousActionProperties settings(
      boolean enabled, boolean bluesky, boolean scheduled, String cron, String zone) {
    var d = AutonomousActionProperties.Bluesky.defaults();
    return new AutonomousActionProperties(
        enabled,
        new AutonomousActionProperties.Bluesky(
            bluesky,
            scheduled,
            cron,
            zone,
            d.maxDraftsPerDay(),
            d.maxPendingDrafts(),
            d.cooldown(),
            d.draftExpiration(),
            d.maxRegenerations(),
            d.regenerationCooldown(),
            d.systemPrompt()));
  }

  private AutonomousOpportunityScheduler scheduler(String cron, String zone, Clock effectiveClock) {
    return new AutonomousOpportunityScheduler(
        settings(true, true, true, cron, zone),
        effectiveClock,
        stopping::get,
        considered::add,
        worker);
  }

  @Test
  void dueOpportunityUsesTheInheritedClockZoneAndStableUtcIdentity() {
    clock.at("2026-11-28T02:59:59Z");
    try (var scheduler = scheduler("0 0 12 * * *", "", clock.withZone(ZoneId.of("Asia/Tokyo")))) {
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      clock.at("2026-11-28T03:00:00Z");
      scheduler.tick();
      scheduler.tick();
      assertThat(worker.pending).hasSize(1);
      assertThat(considered).isEmpty();
      worker.runNext();
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      assertThat(considered).containsExactly("scheduled:bluesky:2026-11-28T03:00:00Z");
      clock.at("2026-11-29T03:00:00Z");
      scheduler.tick();
      worker.runNext();
      assertThat(considered)
          .containsExactly(
              "scheduled:bluesky:2026-11-28T03:00:00Z", "scheduled:bluesky:2026-11-29T03:00:00Z");
    }
  }

  @ParameterizedTest
  @CsvSource({"UTC,Asia/Tokyo,2026-11-28T03:00:00Z", "Asia/Tokyo,UTC,2026-11-28T12:00:00Z"})
  void configuredZoneOverridesTheClockZone(String clockZone, String zone, String due) {
    clock.at("2026-11-28T00:00:00Z");
    try (var scheduler = scheduler("0 0 12 * * *", zone, clock.withZone(ZoneId.of(clockZone)))) {
      clock.at(due);
      scheduler.tick();
      worker.runNext();
      assertThat(considered).containsExactly("scheduled:bluesky:" + due);
    }
  }

  @Test
  void allThreeAdmissionFlagsAreRequired() {
    for (var flags :
        new boolean[][] {
          {false, true, true}, {true, false, true}, {true, true, false}, {false, false, false}
        }) {
      var localWorker = new ManualWorker();
      clock.at("2026-11-28T11:59:59Z");
      try (var scheduler =
          new AutonomousOpportunityScheduler(
              settings(flags[0], flags[1], flags[2], "0 0 12 * * *", ""),
              clock,
              stopping::get,
              considered::add,
              localWorker)) {
        clock.at("2026-11-29T12:00:00Z");
        scheduler.tick();
        assertThat(localWorker.pending).isEmpty();
        assertThat(considered).isEmpty();
      }
    }
  }

  @Test
  void startupAtOrAfterTheDueTimeDoesNotReplayMissedSlots() {
    clock.at("2026-11-28T12:00:00Z");
    try (var scheduler = scheduler("0 0 12 * * *", "", clock)) {
      scheduler.tick();
      clock.at("2026-11-28T12:00:01Z");
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      clock.at("2026-11-29T12:00:00Z");
      scheduler.tick();
      worker.runNext();
      assertThat(considered).containsExactly("scheduled:bluesky:2026-11-29T12:00:00Z");
    }
  }

  @Test
  void aLongClockPauseAdmitsAtMostOneOpportunityAndAdvancesToTheFuture() {
    try (var scheduler = scheduler("0 * * * * *", "", clock)) {
      clock.at("2026-11-28T12:10:45Z");
      scheduler.tick();
      assertThat(worker.pending).hasSize(1);
      worker.runNext();
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      assertThat(considered).hasSize(1);
      clock.at("2026-11-28T12:11:00Z");
      scheduler.tick();
      worker.runNext();
      assertThat(considered).hasSize(2).endsWith("scheduled:bluesky:2026-11-28T12:11:00Z");
    }
  }

  @Test
  void movingTheClockBackwardCannotRepeatAnAlreadyConsideredOccurrence() {
    try (var scheduler = scheduler("0 0 12 * * *", "", clock)) {
      clock.at("2026-11-28T12:00:00Z");
      scheduler.tick();
      worker.runNext();
      clock.at("2026-11-28T11:00:00Z");
      scheduler.tick();
      clock.at("2026-11-28T12:00:00Z");
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      assertThat(considered).hasSize(1);
    }
  }

  @Test
  void aCronWithoutAnyFutureDateDoesNotSpinOrStartWork() {
    try (var scheduler = scheduler("0 0 0 31 2 *", "", clock)) {
      clock.at("2036-12-01T00:00:00Z");
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      assertThat(considered).isEmpty();
    }
  }

  @Test
  void aMissingCivilHourIsSkippedAccordingToTheConfiguredZone() {
    clock.at("2026-03-08T06:59:59Z");
    try (var scheduler = scheduler("0 30 2 * * *", "America/New_York", clock)) {
      clock.at("2026-03-08T07:30:00Z");
      scheduler.tick();
      assertThat(worker.pending).isEmpty();
      clock.at("2026-03-09T06:30:00Z");
      scheduler.tick();
      worker.runNext();
      assertThat(considered).containsExactly("scheduled:bluesky:2026-03-09T06:30:00Z");
    }
  }

  private static final class ManualWorker extends AbstractExecutorService {
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private boolean closed;

    public void execute(Runnable task) {
      if (closed) throw new RejectedExecutionException();
      pending.addLast(task);
    }

    void runNext() {
      assertThat(pending).isNotEmpty();
      pending.removeFirst().run();
    }

    public void shutdown() {
      closed = true;
    }

    public List<Runnable> shutdownNow() {
      closed = true;
      var tasks = List.copyOf(pending);
      pending.clear();
      return tasks;
    }

    public boolean isShutdown() {
      return closed;
    }

    public boolean isTerminated() {
      return closed && pending.isEmpty();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> value;
    private final ZoneId zone;

    MutableClock(Instant instant, ZoneId zone) {
      this(new AtomicReference<>(instant), zone);
    }

    private MutableClock(AtomicReference<Instant> value, ZoneId zone) {
      this.value = value;
      this.zone = zone;
    }

    void at(String instant) {
      value.set(Instant.parse(instant));
    }

    public ZoneId getZone() {
      return zone;
    }

    public Clock withZone(ZoneId zone) {
      return new MutableClock(value, zone);
    }

    public Instant instant() {
      return value.get();
    }
  }
}
