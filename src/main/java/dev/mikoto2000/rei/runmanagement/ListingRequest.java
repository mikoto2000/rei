package dev.mikoto2000.rei.runmanagement;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared validation for shell and connected CLI listing requests. */
public record ListingRequest(String status, boolean all, int limit) {
  public static String status(String value, boolean history) {
    if (value == null) return null;
    String normalized = value.toLowerCase(Locale.ROOT);
    var allowed =
        history
            ? Set.of(
                "queued",
                "running",
                "waiting",
                "waiting_approval",
                "completed",
                "failed",
                "cancelled",
                "unknown")
            : Set.of(
                "running",
                "waiting",
                "waiting_approval",
                "completed",
                "failed",
                "cancelled",
                "interrupted");
    if (!allowed.contains(normalized))
      throw new IllegalArgumentException(
          "Invalid --status: " + value + "; choose " + allowed.stream().sorted().toList());
    return normalized;
  }

  public static int limit(int value) {
    if (value < 1) throw new IllegalArgumentException("--limit must be positive");
    return value;
  }

  public static ListingRequest parse(List<String> words, boolean history) {
    String status = null;
    boolean all = false;
    int limit = 20;
    var seen = new HashSet<String>();
    for (int i = 0; i < words.size(); i++) {
      String option = words.get(i);
      if (!seen.add(option)) throw new IllegalArgumentException("Duplicate option: " + option);
      switch (option) {
        case "--all" -> all = true;
        case "--status" -> {
          if (++i >= words.size()) throw new IllegalArgumentException("Missing --status value");
          status = status(words.get(i), history);
        }
        case "--limit" -> {
          if (!history || ++i >= words.size())
            throw new IllegalArgumentException("Unexpected --limit");
          limit = limit(Integer.parseInt(words.get(i)));
        }
        default -> throw new IllegalArgumentException("Unknown option: " + option);
      }
    }
    return new ListingRequest(status, all, limit);
  }
}
