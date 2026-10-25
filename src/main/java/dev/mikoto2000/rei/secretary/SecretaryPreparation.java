package dev.mikoto2000.rei.secretary;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic prioritization. Unknown effort remains unknown and never fills personal time. */
public final class SecretaryPreparation {
  private SecretaryPreparation() {}

  public static Integer minutes(SecretaryTask task) {
    var values = task.tags().stream().filter(t -> t.startsWith("prep-minutes:")).toList();
    if (values.size() != 1) return null;
    try {
      int value = Integer.parseInt(values.getFirst().substring(13));
      return value > 0 && value <= 10080 ? value : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  public static String load(SecretaryTask task) {
    return task.tags().stream()
        .filter(t -> java.util.Set.of("load:LOW", "load:MEDIUM", "load:HIGH").contains(t))
        .map(t -> t.substring(5))
        .findFirst()
        .orElse("UNKNOWN");
  }

  public static boolean ready(SecretaryTask task, SecretaryLedger ledger) {
    if (task.status() != SecretaryTask.Status.OPEN) return false;
    for (var reference : task.references())
      if (reference.startsWith("after:")) {
        try {
          if (ledger.get(task.projectId(), reference.substring(6)).status()
              != SecretaryTask.Status.DONE) return false;
        } catch (IllegalArgumentException missing) {
          return false;
        }
      }
    return true;
  }

  public static Instant prepareBy(SecretaryTask task, ZoneId zone) {
    var cost = minutes(task);
    return task.due() == null
        ? Instant.MAX
        : task.due()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .minusSeconds(cost == null ? 0 : cost * 60L);
  }

  public static Comparator<SecretaryTask> order(ZoneId zone) {
    return Comparator.comparing((SecretaryTask t) -> prepareBy(t, zone))
        .thenComparingInt(SecretaryTask::priority)
        .thenComparing(t -> minutes(t) == null)
        .thenComparing(t -> load(t).equals("HIGH"))
        .thenComparing(SecretaryTask::updated)
        .thenComparing(SecretaryTask::id);
  }

  public static List<SecretaryTask> choices(
      List<SecretaryTask> tasks, SecretaryLedger ledger, long availableMinutes, ZoneId zone) {
    long budget =
        Math.min(60, availableMinutes / 2); // Keep at least half of declared time unallocated.
    var result = new ArrayList<SecretaryTask>();
    for (var task : tasks.stream().sorted(order(zone)).toList()) {
      var cost = minutes(task);
      if (cost == null || cost > budget || !ready(task, ledger)) continue;
      result.add(task);
      budget -= cost;
      if (result.size() == 3) break;
    }
    return List.copyOf(result);
  }
}
