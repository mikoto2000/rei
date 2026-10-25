package dev.mikoto2000.rei.life;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 利用者が明示した生活制約。空白の予定から利用可能とは推定しない。 */
@Component
public final class LifeContext implements LifePolicy {
  public static final class NotificationDeferred extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotificationDeferred() {
      super("life_policy");
    }
  }

  public enum Availability {
    AVAILABLE,
    BUSY,
    UNAVAILABLE,
    UNKNOWN
  }

  public enum NotificationClass {
    EMERGENCY,
    NORMAL,
    LOW,
    BRIEFING
  }

  public record Window(Set<DayOfWeek> days, String start, String end) {
    public Window {
      days = Set.copyOf(days);
      validateTime(start, end);
    }
  }

  public record Background(String project, String task) {}

  public record ExceptionWindow(Instant start, Instant end, Availability availability) {
    public ExceptionWindow {
      Objects.requireNonNull(availability);
      if (start == null || end == null || !end.isAfter(start))
        throw new IllegalArgumentException("Invalid exception interval");
    }
  }

  public record Settings(
      long revision,
      String zone,
      Set<DayOfWeek> workDays,
      String workStart,
      String workEnd,
      List<Window> notifications,
      List<Window> rest,
      List<Window> personal,
      List<Background> background,
      ExceptionWindow exception,
      Set<String> emergencyNoticeIds) {
    public Settings(
        long revision,
        String zone,
        Set<DayOfWeek> workDays,
        String workStart,
        String workEnd,
        List<Window> notifications,
        List<Window> rest,
        List<Window> personal,
        List<Background> background,
        ExceptionWindow exception) {
      this(
          revision,
          zone,
          workDays,
          workStart,
          workEnd,
          notifications,
          rest,
          personal,
          background,
          exception,
          Set.of());
    }

    public Settings {
      emergencyNoticeIds = emergencyNoticeIds == null ? Set.of() : Set.copyOf(emergencyNoticeIds);
      if (emergencyNoticeIds.size() > 32)
        throw new IllegalArgumentException("Emergency allowlist too large");
      ZoneId.of(zone);
      validateTime(workStart, workEnd);
      workDays = Set.copyOf(workDays);
      notifications = List.copyOf(notifications);
      rest = List.copyOf(rest);
      personal = List.copyOf(personal);
      background = List.copyOf(background);
      if (notifications.size() + rest.size() + personal.size() + background.size() > 256)
        throw new IllegalArgumentException("Too many constraints");
    }
  }

  public record Status(
      Settings settings,
      Instant at,
      boolean working,
      boolean resting,
      Availability availability,
      boolean automaticObservation,
      boolean notification) {}

  public record Lease(Instant started, long revision) {}

  public interface Store {
    Settings load();

    void save(Settings next, long expected);
  }

  private final Store store;
  private final Clock clock;
  private volatile Settings settings;
  private volatile Instant changedAt = Instant.MIN;
  private volatile org.springframework.beans.factory.ObjectProvider<LifeAvailability>
      availabilitySources;

  @org.springframework.beans.factory.annotation.Autowired
  void setAvailabilitySources(
      org.springframework.beans.factory.ObjectProvider<LifeAvailability> sources) {
    availabilitySources = sources;
  }

  private List<LifeAvailability> availabilitySources() {
    var sources = availabilitySources;
    return sources == null ? List.of() : sources.orderedStream().limit(8).toList();
  }

  public LifeContext(Store store, Clock clock) {
    this.store = store;
    this.clock = clock;
    var saved = store.load();
    settings = saved == null ? defaults() : saved;
  }

  public static Settings defaults() {
    return new Settings(
        0,
        "Asia/Tokyo",
        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
        "09:00",
        "18:00",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  public Settings settings() {
    return settings;
  }

  public synchronized Settings configure(Settings value) {
    if (value == null) throw new IllegalArgumentException("Life settings required");
    if (value.revision() != settings.revision())
      throw new IllegalArgumentException("Stale life context revision");
    var next =
        new Settings(
            value.revision() + 1,
            value.zone(),
            value.workDays(),
            value.workStart(),
            value.workEnd(),
            value.notifications(),
            value.rest(),
            value.personal(),
            value.background(),
            value.exception(),
            value.emergencyNoticeIds());
    store.save(next, value.revision());
    changedAt = clock.instant();
    settings = next;
    return next;
  }

  public Status status() {
    var s = settings;
    var now = clock.instant();
    var local = now.atZone(ZoneId.of(s.zone()));
    boolean work = working(s, local), rest = matches(s.rest(), local);
    var availability =
        work || rest
            ? Availability.UNAVAILABLE
            : matches(s.personal(), local) ? Availability.AVAILABLE : Availability.UNKNOWN;
    var exception = s.exception();
    if (!work
        && !rest
        && exception != null
        && !now.isBefore(exception.start())
        && now.isBefore(exception.end())) availability = exception.availability();
    if (availability != Availability.UNAVAILABLE && availability != Availability.BUSY) {
      try {
        for (var source : availabilitySources())
          if (source.busy(now)) {
            availability = Availability.BUSY;
            break;
          }
      } catch (RuntimeException unavailable) {
        availability = Availability.UNKNOWN;
      }
    }
    return new Status(
        s,
        now,
        work,
        rest,
        availability,
        observationAllowed(now),
        notificationAllowed("NORMAL", ""));
  }

  /** Conservative continuous window; overlapping windows never expand the user's declared time. */
  public long availableMinutes() {
    var current = status();
    if (current.availability() != Availability.AVAILABLE) return 0;
    var s = current.settings();
    var now = current.at();
    var zone = ZoneId.of(s.zone());
    var local = now.atZone(zone);
    Instant until = now.plusSeconds(86400);
    for (var w : s.personal()) {
      if (!matches(w, local)) continue;
      var date = local.toLocalDate();
      if (LocalTime.parse(w.start()).isAfter(LocalTime.parse(w.end()))
          && !local.toLocalTime().isBefore(LocalTime.parse(w.start()))) date = date.plusDays(1);
      for (var end : boundaries(date, w.end(), zone))
        if (end.isAfter(now) && end.isBefore(until)) until = end;
    }
    var exception = s.exception();
    if (exception != null) {
      if (!now.isBefore(exception.start())
          && now.isBefore(exception.end())
          && exception.end().isBefore(until)) until = exception.end();
      if (exception.availability() != Availability.AVAILABLE
          && exception.start().isAfter(now)
          && exception.start().isBefore(until)) until = exception.start();
    }
    var protectedWindows = new java.util.ArrayList<>(s.rest());
    protectedWindows.add(new Window(s.workDays(), s.workStart(), s.workEnd()));
    for (var w : protectedWindows)
      for (int day = 0; day <= 1; day++) {
        var date = local.toLocalDate().plusDays(day);
        if (!w.days().contains(date.getDayOfWeek())) continue;
        for (var start : boundaries(date, w.start(), zone))
          if (start.isAfter(now) && start.isBefore(until)) until = start;
      }
    try {
      for (var source : availabilitySources()) {
        var limit = source.availableUntil(now, until);
        if (limit == null || limit.isBefore(now)) return 0;
        if (limit.isBefore(until)) until = limit;
      }
    } catch (RuntimeException unavailable) {
      return 0;
    }
    return s == settings ? Math.max(0, Duration.between(now, until).toMinutes()) : 0;
  }

  private static List<Instant> boundaries(LocalDate date, String time, ZoneId zone) {
    var value = date.atTime(LocalTime.parse(time));
    var rules = zone.getRules();
    var offsets = rules.getValidOffsets(value);
    if (offsets.isEmpty())
      return List.of(Objects.requireNonNull(rules.getTransition(value)).getInstant());
    return offsets.stream().map(value::toInstant).toList();
  }

  public Lease begin() {
    return new Lease(clock.instant(), settings.revision());
  }

  public boolean valid(Lease lease) {
    var snapshot = settings;
    return lease != null
        && lease.revision() == snapshot.revision()
        && observationAllowed(lease.started())
        && snapshot == settings;
  }

  public boolean observationAllowed(Instant since) {
    var now = clock.instant();
    var s = settings;
    if (since == null || since.isAfter(now) || since.isBefore(changedAt)) return false;
    var exception = s.exception();
    if (exception != null
        && exception.availability() == Availability.UNAVAILABLE
        && !now.isBefore(exception.start())
        && since.isBefore(exception.end())) return false;
    var zone = ZoneId.of(s.zone());
    var from = since.atZone(zone);
    var to = now.atZone(zone);
    if (working(s, to) || matches(s.rest(), to)) return false;
    // A suspended operation must not resume after having crossed a protected interval.
    if (Duration.between(since, now).toDays() > 8) return false;
    for (var date = from.toLocalDate().minusDays(1);
        !date.isAfter(to.toLocalDate());
        date = date.plusDays(1)) {
      if (overlaps(s.workDays(), s.workStart(), s.workEnd(), date, zone, since, now)) return false;
      for (var w : s.rest())
        if (overlaps(w.days(), w.start(), w.end(), date, zone, since, now)) return false;
    }
    return true;
  }

  public boolean observationAllowed() {
    return observationAllowed(clock.instant());
  }

  public boolean notificationAllowed(String category, String criterion) {
    // No model-controlled priority or text is a trusted emergency criterion.
    var classification = NotificationClass.valueOf(category);
    var s = settings;
    var local = clock.instant().atZone(ZoneId.of(s.zone()));
    if (classification == NotificationClass.EMERGENCY && s.emergencyNoticeIds().contains(criterion))
      return true;
    return !exceptionBlocks(s, local.toInstant(), true)
        && !working(s, local)
        && !matches(s.rest(), local)
        && (s.notifications().isEmpty() || matches(s.notifications(), local))
        && scheduleAllowsNotification(local.toInstant());
  }

  private boolean scheduleAllowsNotification(Instant at) {
    try {
      for (var source : availabilitySources()) if (source.busy(at)) return false;
    } catch (RuntimeException unavailable) {
      return false;
    }
    return true;
  }

  public boolean backgroundAllowed(String project, String task) {
    var s = settings;
    var local = clock.instant().atZone(ZoneId.of(s.zone()));
    if (matches(s.rest(), local) || exceptionBlocks(s, local.toInstant(), false)) return false;
    return !working(s, local) || s.background().contains(new Background(project, task));
  }

  private static boolean exceptionBlocks(Settings s, Instant at, boolean interrupt) {
    var exception = s.exception();
    return exception != null
        && !at.isBefore(exception.start())
        && at.isBefore(exception.end())
        && (exception.availability() == Availability.UNAVAILABLE
            || interrupt && exception.availability() == Availability.BUSY);
  }

  private static boolean working(Settings s, ZonedDateTime at) {
    return matches(new Window(s.workDays(), s.workStart(), s.workEnd()), at);
  }

  private static boolean matches(List<Window> windows, ZonedDateTime at) {
    return windows.stream().anyMatch(w -> matches(w, at));
  }

  private static boolean matches(Window w, ZonedDateTime at) {
    var start = LocalTime.parse(w.start());
    var end = LocalTime.parse(w.end());
    var time = at.toLocalTime();
    if (start.isBefore(end))
      return w.days().contains(at.getDayOfWeek()) && !time.isBefore(start) && time.isBefore(end);
    return (w.days().contains(at.getDayOfWeek()) && !time.isBefore(start))
        || (w.days().contains(at.minusDays(1).getDayOfWeek()) && time.isBefore(end));
  }

  private static boolean overlaps(
      Set<DayOfWeek> days,
      String start,
      String end,
      LocalDate date,
      ZoneId zone,
      Instant from,
      Instant to) {
    if (!days.contains(date.getDayOfWeek())) return false;
    var a = date.atTime(LocalTime.parse(start)).atZone(zone).toInstant();
    var b =
        date.plusDays(LocalTime.parse(start).isBefore(LocalTime.parse(end)) ? 0 : 1)
            .atTime(LocalTime.parse(end))
            .atZone(zone)
            .toInstant();
    return !to.isBefore(a) && from.isBefore(b);
  }

  private static void validateTime(String start, String end) {
    if (LocalTime.parse(start).equals(LocalTime.parse(end)))
      throw new IllegalArgumentException("Empty or ambiguous interval");
  }
}
