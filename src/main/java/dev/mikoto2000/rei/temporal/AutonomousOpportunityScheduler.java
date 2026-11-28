package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.secretary.SecretaryTick;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.scheduling.support.CronExpression;

/** Admits consideration opportunities to the existing scheduler; never publishes posts. */
public final class AutonomousOpportunityScheduler implements SecretaryTick, AutoCloseable {
  private final boolean enabled;
  private final Clock clock;
  private final ZoneId zone;
  private final CronExpression cron;
  private final BooleanSupplier stopping;
  private final Consumer<String> consideration;
  private final ExecutorService worker;
  private ZonedDateTime next;

  AutonomousOpportunityScheduler(
      AutonomousActionProperties settings,
      Clock clock,
      BooleanSupplier stopping,
      Consumer<String> consideration,
      ExecutorService worker) {
    Objects.requireNonNull(settings);
    this.clock = Objects.requireNonNull(clock);
    this.stopping = Objects.requireNonNull(stopping);
    this.consideration = Objects.requireNonNull(consideration);
    this.worker = Objects.requireNonNull(worker);
    var bluesky = settings.bluesky();
    enabled = settings.enabled() && bluesky.enabled() && bluesky.scheduledEnabled();
    zone = bluesky.zone().isBlank() ? clock.getZone() : ZoneId.of(bluesky.zone());
    cron = CronExpression.parse(bluesky.cron());
    next = cron.next(clock.instant().atZone(zone));
  }

  @Override
  public synchronized void tick() {
    if (!enabled || stopping.getAsBoolean() || next == null) return;
    var now = clock.instant();
    if (now.isBefore(next.toInstant())) return;
    var due = next.toInstant();
    next = cron.next(now.atZone(zone));
    worker.submit(() -> consideration.accept("scheduled:bluesky:" + due));
  }

  @Override
  public void close() {
    worker.shutdownNow();
  }
}
