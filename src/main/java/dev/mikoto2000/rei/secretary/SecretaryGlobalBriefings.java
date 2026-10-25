package dev.mikoto2000.rei.secretary;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** One bounded aggregation on the existing follow-up tick and durable notification outbox. */
@Component
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
@EnableConfigurationProperties(GlobalBriefingProperties.class)
public final class SecretaryGlobalBriefings {
  public record Settings(
      boolean enabled, String morningTime, String eveningTime, String zone, List<String> projects) {
    public Settings {
      projects = List.copyOf(projects);
    }
  }

  public record Report(
      LocalDate date,
      String zone,
      boolean review,
      Map<String, SecretaryFollowups.Report> projects,
      List<String> unavailable,
      boolean partial,
      List<String> cautions) {
    public Report {
      projects = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projects));
      unavailable = List.copyOf(unavailable);
      cautions = List.copyOf(cautions);
    }
  }

  public record Generation(
      String id, String key, Instant generated, List<String> projects, Report report) {
    public Generation {
      projects = List.copyOf(projects);
    }
  }

  private final SecretarySnapshotStore snapshots;
  private final ObjectProvider<SecretaryReports> reports;
  private final Clock clock;
  private final GlobalBriefingProperties properties;
  private LocalDate receiptDate;
  private final Set<String> scheduledReceipts = new java.util.HashSet<>();
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy value) {
    life = value;
  }

  public SecretaryGlobalBriefings(
      SecretaryDatabase database,
      ObjectProvider<SecretaryReports> reports,
      Clock clock,
      GlobalBriefingProperties properties) {
    snapshots = new SecretarySnapshotStore(database);
    this.reports = reports;
    this.clock = clock;
    this.properties = properties;
  }

  public Settings settings() {
    return new Settings(
        properties.enabled(),
        properties.morningTime(),
        properties.eveningTime(),
        properties.zone().isEmpty()
            ? life == null ? clock.getZone().getId() : life.settings().zone()
            : properties.zone(),
        properties.projects());
  }

  public boolean enabled() {
    return properties.enabled();
  }

  public Report report(LocalDate requested, boolean review) {
    var settings = settings();
    var zone = ZoneId.of(settings.zone());
    var date = requested == null ? clock.instant().atZone(zone).toLocalDate() : requested;
    var values = new LinkedHashMap<String, SecretaryFollowups.Report>();
    var unavailable = new ArrayList<String>();
    boolean partial = settings.projects().isEmpty();
    for (var project : settings.projects()) {
      try {
        var value = reports.getObject().report(project, date, review, zone);
        values.put(project, value);
        partial |= value.partial();
      } catch (RuntimeException failure) {
        unavailable.add(project);
        partial = true;
      }
    }
    // A global meeting shares a single human time budget across all selected projects.
    long budget = life == null ? 0 : Math.min(60, life.availableMinutes() / 2);
    var candidates =
        values.values().stream()
            .flatMap(v -> v.sections().getOrDefault("next", List.of()).stream())
            .sorted(SecretaryPreparation.order(zone))
            .toList();
    var selected = new java.util.HashSet<List<String>>();
    for (var task : candidates) {
      var minutes = SecretaryPreparation.minutes(task);
      if (minutes == null || minutes > budget) continue;
      if (selected.add(List.of(task.projectId(), task.id()))) budget -= minutes;
      if (selected.size() == 3) break;
    }
    values.replaceAll(
        (project, value) -> {
          var sections = new LinkedHashMap<>(value.sections());
          sections.put(
              "next",
              sections.getOrDefault("next", List.of()).stream()
                  .filter(t -> selected.contains(List.of(t.projectId(), t.id())))
                  .toList());
          return new SecretaryFollowups.Report(
              value.date(),
              value.zone(),
              value.review(),
              sections,
              value.context(),
              value.partial(),
              value.evidence(),
              value.cautions(),
              value.held());
        });
    var cautions = new ArrayList<String>();
    cautions.add("勤務中の業務予定は不明。対象Projectに登録済みの私用情報だけを集約します。");
    if (settings.projects().isEmpty())
      cautions.add("rei.secretary.briefings.projects に対象Projectを指定してください。");
    if (partial) cautions.add("一部のProject・情報源を取得できないか、対象が未設定です。");
    return new Report(date, zone.getId(), review, values, unavailable, partial, cautions);
  }

  public synchronized Generation generate(LocalDate requested, boolean review, String key) {
    required(key);
    var settings = settings();
    var date =
        requested == null
            ? clock.instant().atZone(ZoneId.of(settings.zone())).toLocalDate()
            : requested;
    var saved = byKey(key);
    if (saved != null) return compatible(saved, date, review, settings);
    var value =
        new Generation(
            UUID.randomUUID().toString(),
            key,
            clock.instant(),
            settings.projects(),
            report(date, review));
    snapshots.save(value.id(), SecretaryNotifications.GLOBAL_SCOPE, key, value.generated(), value);
    return compatible(java.util.Objects.requireNonNull(byKey(key)), date, review, settings);
  }

  private Generation byKey(String key) {
    return SecretarySnapshotStore.decode(
        snapshots.byKey(SecretaryNotifications.GLOBAL_SCOPE, key), Generation.class);
  }

  private static Generation compatible(
      Generation saved, LocalDate date, boolean review, Settings settings) {
    if (!saved.report().date().equals(date)
        || saved.report().review() != review
        || !scopeMatches(saved, settings)) throw new SecretaryConflictException();
    return saved;
  }

  private static boolean scopeMatches(Generation saved, Settings settings) {
    return Set.copyOf(saved.projects()).equals(Set.copyOf(settings.projects()))
        && saved.report().zone().equals(settings.zone());
  }

  public Generation show(String id) {
    required(id);
    var value =
        SecretarySnapshotStore.decode(
            snapshots.byId(SecretaryNotifications.GLOBAL_SCOPE, id), Generation.class);
    if (value == null) throw new IllegalArgumentException("Global briefing not found");
    return value;
  }

  public List<Generation> history(int limit) {
    return snapshots.list(SecretaryNotifications.GLOBAL_SCOPE, limit).stream()
        .map(v -> SecretarySnapshotStore.decode(v, Generation.class))
        .toList();
  }

  public int deleteHistory() {
    return snapshots.delete(SecretaryNotifications.GLOBAL_SCOPE);
  }

  synchronized void schedule(Consumer<SecretaryNotifications.Notice> enqueue) {
    var settings = settings();
    if (!settings.enabled()) return;
    var now = clock.instant().atZone(ZoneId.of(settings.zone()));
    if (!now.toLocalDate().equals(receiptDate)) {
      scheduledReceipts.clear();
      receiptDate = now.toLocalDate();
    }
    schedule(settings, now.toLocalDate(), now.toLocalTime(), false, enqueue);
    schedule(settings, now.toLocalDate(), now.toLocalTime(), true, enqueue);
  }

  private void schedule(
      Settings settings,
      LocalDate date,
      LocalTime time,
      boolean review,
      Consumer<SecretaryNotifications.Notice> enqueue) {
    var configured = review ? settings.eveningTime() : settings.morningTime();
    if (configured.isEmpty() || time.isBefore(LocalTime.parse(configured))) return;
    String kind = review ? "GLOBAL_REVIEW" : "GLOBAL_BRIEFING";
    String key = "scheduled:" + kind + ":" + date;
    String id =
        UUID.nameUUIDFromBytes(
                (SecretaryNotifications.GLOBAL_SCOPE + ":" + key)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
    if (scheduledReceipts.contains(id)) return;
    if (snapshots.noticeExists(id)) {
      scheduledReceipts.add(id);
      return;
    }
    var old = byKey(key);
    if (old != null && !scopeMatches(old, settings)) return;
    var generation = generate(date, review, key);
    int due =
        generation.report().projects().values().stream()
            .mapToInt(r -> r.sections().getOrDefault("today", List.of()).size())
            .sum();
    int done =
        generation.report().projects().values().stream()
            .mapToInt(r -> r.sections().getOrDefault("completed", List.of()).size())
            .sum();
    enqueue.accept(
        new SecretaryNotifications.Notice(
            id,
            SecretaryNotifications.GLOBAL_SCOPE,
            null,
            kind,
            (review ? "夕" : "朝")
                + "のブリーフィング "
                + date
                + ": Project="
                + generation.projects().size()
                + " 今日="
                + due
                + " 完了="
                + done
                + "; /secretary briefing-show "
                + generation.id()
                + (generation.report().partial() ? "; 一部取得できない情報があります" : ""),
            3,
            "INBOX",
            clock.instant(),
            generation.id()));
    scheduledReceipts.add(id);
  }

  boolean eligible(SecretaryNotifications.Notice notice) {
    if (!enabled()
        || notice.reportId() == null
        || !Set.of("GLOBAL_BRIEFING", "GLOBAL_REVIEW").contains(notice.kind())) return false;
    var saved =
        SecretarySnapshotStore.decode(
            snapshots.byId(SecretaryNotifications.GLOBAL_SCOPE, notice.reportId()),
            Generation.class);
    var settings = settings();
    return saved != null
        && scopeMatches(saved, settings)
        && saved
            .report()
            .date()
            .equals(clock.instant().atZone(ZoneId.of(settings.zone())).toLocalDate());
  }

  private static void required(String value) {
    if (value == null || value.isBlank() || value.length() > 128)
      throw new IllegalArgumentException("Briefing ID/key required (max 128)");
  }
}
