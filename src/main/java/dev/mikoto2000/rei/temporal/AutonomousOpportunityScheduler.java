package dev.mikoto2000.rei.temporal;

import dev.mikoto2000.rei.autonomous.AutonomousActionProperties;
import dev.mikoto2000.rei.secretary.SecretaryTick;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.scheduling.support.CronExpression;

/** Admits consideration opportunities to the existing scheduler; never publishes posts. */
public final class AutonomousOpportunityScheduler implements SecretaryTick, AutoCloseable {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AutonomousOpportunityScheduler.class);
  private final boolean enabled;
  private final Clock clock;
  private final ZoneId zone;
  private final CronExpression cron;
  private final BooleanSupplier stopping;
  private final Consumer<String> consideration;
  private final ExecutorService worker;
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private ZonedDateTime next;
  private Future<?> active;

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
    if (!enabled || closed.get() || stopping.getAsBoolean() || next == null) return;
    var now = clock.instant();
    if (now.isBefore(next.toInstant())) return;
    var due = next.toInstant();
    next = cron.next(now.atZone(zone));
    if (!busy.compareAndSet(false, true)) return;
    try {
      active =
          worker.submit(() -> {
            try {
              if (!closed.get() && !stopping.getAsBoolean())
                consideration.accept("scheduled:bluesky:" + due);
            } catch (RuntimeException failed) {
              if (!closed.get()) log.warn("Autonomous consideration failed");
            } finally {
              busy.set(false);
            }
          });
    } catch (RejectedExecutionException unavailable) {
      busy.set(false);
      if (!closed.get()) log.warn("Autonomous consideration worker unavailable");
    }
  }

  @Override
  @PreDestroy
  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    synchronized (this) {
      if (active != null) active.cancel(true);
      worker.shutdownNow();
    }
    try {
      worker.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
