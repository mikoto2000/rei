package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.event.CredentialRedactor;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Small, safe wire contract shared by Shell and terminal clients. No snapshot or tool output. */
public record ResumeOverview(
    String projectId, String projectName, long count, Candidate latest, String fingerprint) {
  public record Candidate(
      String taskId,
      long revision,
      long headRevision,
      String savedAt,
      String savedStatus,
      String request,
      String completed,
      String remaining,
      String notice,
      boolean fallback) {
    public Candidate {
      if (taskId == null || !taskId.matches("[A-Za-z0-9_.:-]{1,128}"))
        throw new IllegalArgumentException("Unsafe checkpoint task identity");
      savedStatus = safe(savedStatus, 24);
      request = safe(request, 80);
      completed = safe(completed, 120);
      remaining = safe(remaining, 120);
      notice = safe(notice, 100);
    }

    public Candidate(
        String taskId,
        long revision,
        long headRevision,
        Instant savedAt,
        String savedStatus,
        String request,
        String completed,
        String remaining,
        String notice,
        boolean fallback) {
      this(
          taskId,
          revision,
          headRevision,
          savedAt.toString(),
          savedStatus,
          request,
          completed,
          remaining,
          notice,
          fallback);
    }
  }

  public record ProjectCount(String projectId, String projectName, long count) {}

  public static String safe(String value, int limit) {
    if (value == null || value.isBlank()) return "未記録";
    String normalized =
        value
            .replaceAll("\\x1B\\][^\\x07\\x1B]*(?:\\x07|\\x1B\\\\)", "")
            .replaceAll("(?:\\x1B\\[|\\x9B)[0-?]*[ -/]*[@-~]", "");
    normalized = normalized.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
    String text =
        CredentialRedactor.redact(normalized)
            .replaceAll("(?i)\\b(?:Bearer|Basic)\\s+\\S+", "[REDACTED]")
            .replaceAll("\\s+", " ")
            .strip();
    if (text.isBlank()) return "未記録";
    return text.codePointCount(0, text.length()) <= limit
        ? text
        : text.substring(0, text.offsetByCodePoints(0, limit - 1)) + "…";
  }

  public String detail(ZoneId zone) {
    if (count == 0 || latest == null) return "";
    var c = latest;
    return "Project: "
        + safe(projectName, 80)
        + "\n最新の再開候補（全"
        + count
        + "件）\n\n"
        + "  依頼: "
        + safe(c.request(), 80)
        + "\n  保存: "
        + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z", Locale.ENGLISH)
            .withZone(zone)
            .format(Instant.parse(c.savedAt()))
        + "\n  状態（保存時）: "
        + c.savedStatus()
        + "\n  完了: "
        + c.completed()
        + "\n  残り: "
        + c.remaining()
        + "\n  現在: "
        + c.notice()
        + "\n\n  詳細: /resume show "
        + c.taskId()
        + "\n  再開: /resume "
        + c.taskId()
        + (count > 1 ? "\nほか" + (count - 1) + "件: /resume list" : "");
  }

  public static String summary(List<ProjectCount> counts) {
    if (counts.isEmpty()) return "";
    return "前回からの再開候補:\n"
        + counts.stream()
            .map(p -> "  " + safe(p.projectName(), 80) + "  " + p.count() + " 件")
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  /** One instance per connection. Empty results also invalidate previous display history. */
  public static final class DisplayHistory {
    private final Map<String, String> seen = new HashMap<>();
    private String selected;

    public synchronized String present(ResumeOverview overview, ZoneId zone, boolean force) {
      boolean same = Objects.equals(selected, overview.projectId());
      selected = overview.projectId();
      String previous = seen.get(overview.projectId());
      String text =
          overview.count() == 0
              ? ""
              : force || !Objects.equals(previous, overview.fingerprint())
                  ? overview.detail(zone)
                  : same
                      ? ""
                      : "Project: "
                          + safe(overview.projectName(), 80)
                          + "\n再開候補 "
                          + overview.count()
                          + " 件（前回表示から変更なし）\n詳細: /resume list";
      seen.put(overview.projectId(), overview.fingerprint());
      return text;
    }
  }
}
