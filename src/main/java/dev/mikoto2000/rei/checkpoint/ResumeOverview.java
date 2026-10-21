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

/**
 * 作業の中断と再開で受け渡す再開概要を表す。
 *
 * <p>Small, safe wire contract shared by Shell and terminal clients. No snapshot or tool output.
 *
 * @param projectId 所属プロジェクトの識別子
 * @param projectName プロジェクト名前
 * @param count 件数
 * @param latest 最新
 * @param fingerprint 要求内容の照合値
 */
public record ResumeOverview(
    String projectId, String projectName, long count, Candidate latest, String fingerprint) {
  /**
   * 作業の中断と再開で受け渡す候補を表す。
   *
   * @param taskId タスク識別子
   * @param revision 改訂番号
   * @param headRevision 先頭改訂番号
   * @param savedAt 保存済み日時
   * @param savedStatus 保存済み状態
   * @param request 要求
   * @param completed 完了
   * @param remaining 残量
   * @param notice 通知
   * @param fallback 代替手段
   */
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
    /** 受け取った情報からCandidateを作成する。 */
    public Candidate {
      if (taskId == null || !taskId.matches("[A-Za-z0-9_.:-]{1,128}"))
        throw new IllegalArgumentException("Unsafe checkpoint task identity");
      savedStatus = safe(savedStatus, 24);
      request = safe(request, 80);
      completed = safe(completed, 120);
      remaining = safe(remaining, 120);
      notice = safe(notice, 100);
    }

    /** 作業の中断と再開を利用するため、指定を補って共通の初期化へ引き渡す。 */
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

  /**
   * 作業の中断と再開で受け渡すプロジェクト件数を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param projectName プロジェクト名前
   * @param count 件数
   */
  public record ProjectCount(String projectId, String projectName, long count) {}

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
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

  /** 作業の中断と再開で用いる詳細を返す。 */
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

  /** 作業の中断と再開で用いる要約を返す。 */
  public static String summary(List<ProjectCount> counts) {
    if (counts.isEmpty()) return "";
    return "前回からの再開候補:\n"
        + counts.stream()
            .map(p -> "  " + safe(p.projectName(), 80) + "  " + p.count() + " 件")
            .collect(java.util.stream.Collectors.joining("\n"));
  }

  /**
   * 作業の中断と再開に必要な表示履歴を扱う。
   *
   * <p>One instance per connection. Empty results also invalidate previous display history.
   */
  public static final class DisplayHistory {
    /** 確認済み。 */
    private final Map<String, String> seen = new HashMap<>();

    /** 選択中。 */
    private String selected;

    /** 作業の中断と再開で用いる存在するを返す。 */
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
