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
      boolean enabled,
      String morningTime,
      String eveningTime,
      String zone,
      List<String> projects,
      List<String> independentProjects,
      boolean allProjects) {
    public Settings {
      projects = List.copyOf(projects);
      independentProjects = List.copyOf(independentProjects);
    }
  }

  public record Report(
      LocalDate date,
      String zone,
      boolean review,
      Map<String, SecretaryFollowups.Report> projects,
      List<String> unavailable,
      boolean partial,
      List<String> cautions,
      SecretaryMeeting.View meeting) {
    public Report(
        LocalDate date,
        String zone,
        boolean review,
        Map<String, SecretaryFollowups.Report> projects,
        List<String> unavailable,
        boolean partial,
        List<String> cautions) {
      this(date, zone, review, projects, unavailable, partial, cautions, null);
    }

    public Report {
      projects = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(projects));
      unavailable = List.copyOf(unavailable);
      cautions = List.copyOf(cautions);
      if (meeting == null)
        meeting =
            SecretaryMeeting.aggregate(
                date,
                zone,
                review,
                projects.values().stream().map(SecretaryFollowups.Report::meeting).toList(),
                partial);
    }
  }

  public record Generation(
      String id,
      String key,
      Instant generated,
      List<String> projects,
      Report report,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          String independentProject) {
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
  private volatile ObjectProvider<SecretaryProjects> projectQueries;

  @Autowired
  void setProjectQueries(ObjectProvider<SecretaryProjects> queries) {
    projectQueries = queries;
  }

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
    var sources = properties.projects();
    if (sources.isEmpty()) {
      var service = projectQueries == null ? null : projectQueries.getIfAvailable();
      sources =
          service == null
              ? List.of()
              : service.registeredProjectIds().stream().sorted().distinct().toList();
    }
    return new Settings(
        properties.enabled(),
        properties.morningTime(),
        properties.eveningTime(),
        zone(),
        sources,
        properties.independentProjects(),
        properties.projects().isEmpty());
  }

  private String zone() {
    return properties.zone().isEmpty()
        ? life == null ? clock.getZone().getId() : life.settings().zone()
        : properties.zone();
  }

  private static List<String> members(Settings settings, String independentProject) {
    return independentProject == null
        ? settings.projects().stream()
            .filter(p -> !settings.independentProjects().contains(p))
            .toList()
        : settings.projects().contains(independentProject)
                && settings.independentProjects().contains(independentProject)
            ? List.of(independentProject)
            : List.of();
  }

  public boolean enabled() {
    return properties.enabled();
  }

  /** One explicit decision across the combined project scope. IDs include their owning project. */
  public synchronized SecretaryMeeting.Decision confirmMeeting(
      LocalDate requested, boolean review, String key, List<String> selected) {
    var date =
        requested == null ? clock.instant().atZone(ZoneId.of(zone())).toLocalDate() : requested;
    var configuration = settings();
    return new SecretaryMeetingDecisions(snapshots, clock)
        .confirm(
            SecretaryNotifications.GLOBAL_SCOPE,
            members(configuration, null),
            date,
            review,
            key,
            selected,
            () ->
                report(date, review).meeting().sections().values().stream()
                    .flatMap(List::stream)
                    .filter(SecretaryMeeting::selectable)
                    .map(w -> w.projectId() + "::" + w.id())
                    .collect(java.util.stream.Collectors.toSet()));
  }

  public Report report(LocalDate requested, boolean review) {
    var settings = settings();
    return report(requested, review, settings, members(settings, null));
  }

  private Report report(
      LocalDate requested, boolean review, Settings settings, List<String> projects) {
    var zone = ZoneId.of(settings.zone());
    var date = requested == null ? clock.instant().atZone(zone).toLocalDate() : requested;
    var values = new LinkedHashMap<String, SecretaryFollowups.Report>();
    var unavailable = new ArrayList<String>();
    for (var project : projects) {
      try {
        var value = reports.getObject().report(project, date, review, zone);
        values.put(project, value);
      } catch (RuntimeException failure) {
        unavailable.add(project);
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
              value.held(),
              value.meeting());
        });
    return aggregate(date, zone.getId(), review, values, unavailable);
  }

  private static Report aggregate(
      LocalDate date,
      String zone,
      boolean review,
      Map<String, SecretaryFollowups.Report> values,
      List<String> unavailable) {
    boolean partial =
        values.isEmpty()
            || !unavailable.isEmpty()
            || values.values().stream().anyMatch(SecretaryFollowups.Report::partial);
    var cautions = new ArrayList<String>();
    cautions.add("勤務中の業務予定は不明。対象Projectに登録済みの私用情報だけを集約します。");
    if (values.isEmpty() && unavailable.isEmpty())
      cautions.add("集約対象の登録済みProjectがありません。独立指定したProjectは別の通知になります。");
    if (partial) cautions.add("一部のProject・情報源を取得できないか、対象が未設定です。");
    return new Report(date, zone, review, values, unavailable, partial, cautions);
  }

  private static Report subset(Report report, List<String> projects) {
    var values = new LinkedHashMap<String, SecretaryFollowups.Report>();
    projects.forEach(
        p -> {
          if (report.projects().containsKey(p)) values.put(p, report.projects().get(p));
        });
    return aggregate(
        report.date(),
        report.zone(),
        report.review(),
        values,
        report.unavailable().stream().filter(projects::contains).toList());
  }

  public synchronized Generation generate(LocalDate requested, boolean review, String key) {
    var settings = settings();
    var date =
        requested == null
            ? clock.instant().atZone(ZoneId.of(settings.zone())).toLocalDate()
            : requested;
    return generate(
        date,
        review,
        key,
        settings,
        null,
        () -> report(date, review, settings, members(settings, null)));
  }

  private Generation generate(
      LocalDate requested,
      boolean review,
      String key,
      Settings settings,
      String independentProject,
      java.util.function.Supplier<Report> report) {
    required(key);
    var date =
        requested == null
            ? clock.instant().atZone(ZoneId.of(settings.zone())).toLocalDate()
            : requested;
    var saved = byKey(key);
    if (saved != null) return compatible(saved, date, review, settings, independentProject);
    var value =
        new Generation(
            UUID.randomUUID().toString(),
            key,
            clock.instant(),
            members(settings, independentProject),
            report.get(),
            independentProject);
    snapshots.save(value.id(), SecretaryNotifications.GLOBAL_SCOPE, key, value.generated(), value);
    return compatible(
        java.util.Objects.requireNonNull(byKey(key)), date, review, settings, independentProject);
  }

  private Generation byKey(String key) {
    return SecretarySnapshotStore.decode(
        snapshots.byKey(SecretaryNotifications.GLOBAL_SCOPE, key), Generation.class);
  }

  private static Generation compatible(
      Generation saved,
      LocalDate date,
      boolean review,
      Settings settings,
      String independentProject) {
    if (!saved.report().date().equals(date)
        || saved.report().review() != review
        || !java.util.Objects.equals(saved.independentProject(), independentProject)
        || !scopeMatches(saved, settings)) throw new SecretaryConflictException();
    return saved;
  }

  private static boolean scopeMatches(Generation saved, Settings settings) {
    return Set.copyOf(saved.projects())
            .equals(Set.copyOf(members(settings, saved.independentProject())))
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
    if (!enabled()) return;
    var now = clock.instant().atZone(ZoneId.of(zone()));
    if (!now.toLocalDate().equals(receiptDate)) {
      scheduledReceipts.clear();
      receiptDate = now.toLocalDate();
    }
    schedule(now.toLocalDate(), now.toLocalTime(), false, enqueue);
    schedule(now.toLocalDate(), now.toLocalTime(), true, enqueue);
  }

  private void schedule(
      LocalDate date,
      LocalTime time,
      boolean review,
      Consumer<SecretaryNotifications.Notice> enqueue) {
    var configured = review ? properties.eveningTime() : properties.morningTime();
    if (configured.isEmpty() || time.isBefore(LocalTime.parse(configured))) return;
    var groups = new ArrayList<String>();
    groups.add(null);
    groups.addAll(properties.independentProjects());
    if (groups.stream().allMatch(p -> scheduledReceipts.contains(receipt(key(date, review, p)))))
      return;
    var settings = settings();
    // One preparation budget is shared even when reports are split into independent notices.
    var batch =
        new java.util.function.Supplier<Report>() {
          private Report cached;

          public Report get() {
            if (cached == null) cached = report(date, review, settings, settings.projects());
            return cached;
          }
        };
    for (var group : groups) schedule(settings, date, review, group, batch, enqueue);
  }

  private static String key(LocalDate date, boolean review, String independentProject) {
    return "scheduled:"
        + (review ? "GLOBAL_REVIEW" : "GLOBAL_BRIEFING")
        + ":"
        + date
        + (independentProject == null ? "" : ":project:" + receipt(independentProject));
  }

  private static String receipt(String key) {
    return UUID.nameUUIDFromBytes(
            (SecretaryNotifications.GLOBAL_SCOPE + ":" + key)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .toString();
  }

  private void schedule(
      Settings settings,
      LocalDate date,
      boolean review,
      String independentProject,
      java.util.function.Supplier<Report> batch,
      Consumer<SecretaryNotifications.Notice> enqueue) {
    String kind = review ? "GLOBAL_REVIEW" : "GLOBAL_BRIEFING";
    String key = key(date, review, independentProject);
    String id = receipt(key);
    if (scheduledReceipts.contains(id)) return;
    if (snapshots.noticeExists(id)) {
      scheduledReceipts.add(id);
      return;
    }
    var members = members(settings, independentProject);
    if (members.isEmpty()) {
      scheduledReceipts.add(id);
      return;
    }
    var old = byKey(key);
    if (old != null
        && (!scopeMatches(old, settings)
            || !java.util.Objects.equals(old.independentProject(), independentProject))) {
      scheduledReceipts.add(id);
      return;
    }
    var generation =
        generate(
            date, review, key, settings, independentProject, () -> subset(batch.get(), members));
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
                + (independentProject == null ? "[全体] " : "[独立Project=" + independentProject + "] ")
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
