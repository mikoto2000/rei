package dev.mikoto2000.rei.secretary;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared explicit decision receipts in the existing bounded snapshot store. */
final class SecretaryMeetingDecisions {
  private final SecretarySnapshotStore snapshots;
  private final Clock clock;

  SecretaryMeetingDecisions(SecretarySnapshotStore snapshots, Clock clock) {
    this.snapshots = snapshots;
    this.clock = clock;
  }

  SecretaryMeeting.Decision confirm(
      String project,
      List<String> projects,
      LocalDate date,
      boolean review,
      String key,
      List<String> selected,
      Supplier<Set<String>> available) {
    if (key == null
        || key.isBlank()
        || key.length() > 128
        || selected == null
        || selected.size() > 3
        || new HashSet<>(selected).size() != selected.size()
        || selected.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 512))
      throw new IllegalArgumentException(
          "A key and zero to three distinct meeting IDs are required");
    var scope = "meeting:" + project;
    var saved =
        SecretarySnapshotStore.decode(snapshots.byKey(scope, key), SecretaryMeeting.Decision.class);
    if (saved != null) {
      if (!saved.date().equals(date)
          || saved.review() != review
          || !saved.selected().equals(selected)
          || !Set.copyOf(saved.projects()).equals(Set.copyOf(projects)))
        throw new SecretaryConflictException();
      return saved;
    }
    if (!available.get().containsAll(selected))
      throw new IllegalArgumentException(
          "Meeting selection is stale or unavailable; refresh first");
    var value =
        new SecretaryMeeting.Decision(
            UUID.randomUUID().toString(),
            project,
            date,
            review,
            key,
            clock.instant(),
            selected,
            projects);
    snapshots.save(value.id(), scope, key, value.saved(), value);
    var persisted =
        SecretarySnapshotStore.decode(snapshots.byKey(scope, key), SecretaryMeeting.Decision.class);
    if (!value.equals(persisted)) throw new SecretaryConflictException();
    return persisted;
  }

  SecretaryMeeting.Decision latest(String project, LocalDate date, boolean review) {
    return snapshots.list("meeting:" + project, 100).stream()
        .map(v -> SecretarySnapshotStore.decode(v, SecretaryMeeting.Decision.class))
        .filter(v -> v.date().equals(date) && v.review() == review)
        .findFirst()
        .orElse(null);
  }

  SecretaryMeeting.Decision forProject(String project, LocalDate date, boolean review) {
    var local = latest(project, date, review);
    var global = latest(SecretaryNotifications.GLOBAL_SCOPE, date, review);
    if (global == null
        || !global.projects().contains(project)
        || local != null && !local.saved().isBefore(global.saved())) return local;
    var prefix = project + "::";
    return new SecretaryMeeting.Decision(
        global.id(),
        project,
        date,
        review,
        global.key(),
        global.saved(),
        global.selected().stream()
            .filter(s -> s.startsWith(prefix))
            .map(s -> s.substring(prefix.length()))
            .toList());
  }
}
