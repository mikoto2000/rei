package dev.mikoto2000.rei.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 作業状況の把握で受け渡す時系列作業状況根拠を表す。
 *
 * <p>Bounded chronological aggregation. Adjacent equivalent observations retain every original ID.
 *
 * @param windowStart 範囲開始
 * @param windowEnd 範囲終了
 * @param groups グループ一覧
 * @param observationIds 観測内容識別子一覧
 * @param executions 実行一覧
 * @param fingerprint 要求内容の照合値
 */
public record TemporalActivityEvidence(
    Instant windowStart,
    Instant windowEnd,
    List<Group> groups,
    List<String> observationIds,
    List<WorkActivityInference.Execution> executions,
    String fingerprint) {
  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  /**
   * 作業状況の把握で受け渡す事実一覧を表す。
   *
   * @param process プロセス
   * @param title タイトル
   * @param windowId 範囲識別子
   * @param projectId 所属プロジェクトの識別子
   * @param project プロジェクト
   * @param branch ブランチ
   * @param commit 確定
   * @param workRevision 作業改訂番号
   * @param category 分類
   * @param content 内容
   * @param candidate 候補
   * @param confidence 確信度
   * @param visionState 画像認識状態
   * @param continuity 継続性
   * @param desktop デスクトップ
   * @param input 入力
   */
  public record Facts(
      String process,
      String title,
      String windowId,
      String projectId,
      String project,
      String branch,
      String commit,
      long workRevision,
      String category,
      String content,
      String candidate,
      double confidence,
      String visionState,
      String continuity,
      List<ActivityRecord.Activity> desktop,
      ActivityEvidence.InputReference input) {}

  /**
   * 作業状況の把握で受け渡すグループを表す。
   *
   * @param first 最初
   * @param last 直近
   * @param observationIds 観測内容識別子一覧
   * @param facts 事実一覧
   */
  public record Group(Instant first, Instant last, List<String> observationIds, Facts facts) {
    /** 作業状況の把握で用いる所属プロジェクトの識別子を返す。 */
    public String projectId() {
      return facts.projectId();
    }
  }

  /** 利用先に渡せるよう、時系列作業状況根拠を組み立てる。 */
  public static TemporalActivityEvidence build(
      List<ActivityRecord> records,
      List<WorkActivityInference.Execution> executions,
      Instant start,
      Instant end) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (records.size() > 120 || executions.size() > 64)
      throw new IllegalArgumentException("Temporal evidence limit exceeded");
    var sorted =
        records.stream()
            .filter(r -> !r.capturedAt().isBefore(start) && !r.capturedAt().isAfter(end))
            .sorted(
                Comparator.comparing(ActivityRecord::capturedAt).thenComparing(ActivityRecord::id))
            .toList();
    var seen = new HashSet<String>();
    var groups = new ArrayList<Group>();
    var ids = new ArrayList<String>();
    var events = new LinkedHashMap<String, WorkActivityInference.Execution>();
    for (var execution : executions)
      if (execution.eventId() != null
          && !execution.at().isBefore(start)
          && !execution.at().isAfter(end)) events.put(execution.eventId(), execution);
    for (var r : sorted) {
      if (!seen.add(r.id())) continue;
      ids.add(r.id());
      var facts = facts(r, end);
      if (!groups.isEmpty()
          && groups.getLast().facts().equals(facts)
          && Duration.between(groups.getLast().last(), r.capturedAt()).getSeconds() <= 60) {
        var previous = groups.removeLast();
        var combined = new ArrayList<>(previous.observationIds());
        combined.add(r.id());
        groups.add(new Group(previous.first(), r.capturedAt(), List.copyOf(combined), facts));
      } else groups.add(new Group(r.capturedAt(), r.capturedAt(), List.of(r.id()), facts));
      var evidence = r.detection() == null ? null : r.detection().evidence();
      if (evidence != null)
        for (var e : evidence.events())
          if (e.eventId() != null && !e.at().isBefore(start) && !e.at().isAfter(end))
            events.putIfAbsent(
                e.eventId(),
                new WorkActivityInference.Execution(
                    e.eventId(),
                    e.at(),
                    e.projectId(),
                    e.kind(),
                    "REI",
                    "RECORDED",
                    "",
                    e.sessionId(),
                    e.turnId(),
                    e.runId()));
    }
    var ordered =
        events.values().stream()
            .sorted(
                Comparator.comparing(WorkActivityInference.Execution::at)
                    .thenComparing(WorkActivityInference.Execution::eventId)
                    .reversed())
            .limit(64)
            .sorted(
                Comparator.comparing(WorkActivityInference.Execution::at)
                    .thenComparing(WorkActivityInference.Execution::eventId))
            .toList();
    // Counts saturate at two: repeated observations add provenance, not a new semantic reason to
    // call a model.
    var semantic =
        groups.stream()
            .map(g -> List.of(g.facts(), Math.min(2, g.observationIds().size())))
            .toList();
    return new TemporalActivityEvidence(
        start,
        end,
        List.copyOf(groups),
        List.copyOf(ids),
        ordered,
        hash(List.of(semantic, ordered)));
  }

  /** 作業状況の把握で用いる事実一覧を返す。 */
  private static Facts facts(ActivityRecord r, Instant end) {
    var d = r.detection();
    var e = d == null ? null : d.evidence();
    var w = e == null ? null : e.workContext();
    var git = w == null ? null : w.git();
    var roles = new ActivityRolePolicy().classify(r);
    var a = roles.primary();
    var fg = r.foreground();
    var vision = VisionDiagnostics.of(d).foreground();
    var background = VisionDiagnostics.of(d).background();
    var context = background == null ? null : background.context();
    var desktop =
        context != null
                && Objects.equals(context.observationId(), r.id())
                && Objects.equals(context.observedAt(), r.capturedAt())
                && !context.observedAt().isAfter(end)
                && Duration.between(context.observedAt(), end).getSeconds() <= 300
                && (background.timing() == null
                    || background.timing().completedAt() == null
                    || !background.timing().completedAt().isAfter(end))
            ? context.candidates().stream()
                .map(
                    a2 ->
                        new ActivityRecord.Activity(
                            clean(a2.monitor()),
                            clean(a2.type()),
                            clean(a2.application()),
                            clean(a2.service()),
                            clean(a2.contentTitle()),
                            clean(a2.projectCandidate())))
                .toList()
            : List.<ActivityRecord.Activity>of();
    return new Facts(
        clean(fg == null ? null : fg.processName()),
        clean(fg == null ? null : fg.windowTitle()),
        clean(fg == null ? null : fg.windowId()),
        clean(e == null ? null : e.projectId()),
        clean(e == null ? null : e.projectName()),
        clean(git == null ? null : git.branch()),
        clean(git == null ? null : git.commit()),
        w == null ? 0 : w.revision(),
        clean(a == null ? null : a.type()),
        clean(a == null ? null : a.contentTitle()),
        clean(a == null ? null : a.projectCandidate()),
        roles.confidence(),
        vision == null ? "UNKNOWN" : vision.state().name(),
        clean(r.continuityId()),
        desktop,
        e == null ? null : e.input());
  }

  /** 作業状況の把握で用いる規則推定を返す。 */
  public WorkActivityInference ruleInference() {
    String project = "",
        projectId = "",
        text = "観測不足または証拠が混在しているため、具体的な作業は推定不能です。",
        status = "UNKNOWN";
    double confidence = 0;
    if (observationIds.size() >= 2 && !groups.isEmpty() && continuous()) {
      var distinct = groups.stream().map(g -> g.facts().projectId()).distinct().toList();
      var f = groups.getLast().facts();
      boolean strict = groups.stream().allMatch(g -> sameProject(g.facts()));
      boolean anchored =
          augmented()
              && groups.stream()
                  .allMatch(
                      g ->
                          workCategory(g.facts().category())
                              && (g.facts().candidate().isBlank() || sameProject(g.facts())))
              && groups.stream()
                  .anyMatch(g -> development(g.facts().category()) && sameProject(g.facts()));
      if (distinct.size() == 1
          && !f.projectId().isBlank()
          && !f.project().isBlank()
          && (strict || anchored)
          && groups.stream()
              .allMatch(
                  g ->
                      g.facts().confidence() >= .5
                          && !g.facts().category().isBlank()
                          && !"unknown".equals(g.facts().category()))) {
        project = f.project();
        projectId = f.projectId();
        text =
            project
                + " の"
                + (f.content().isBlank() ? f.category() : "「" + f.content() + "」")
                + "関連画面を伴う作業の可能性があります。";
        if (augmented()) {
          var transitions =
              groups.stream()
                  .map(g -> g.facts().content())
                  .filter(t -> !t.isBlank())
                  .map(t -> t.substring(0, Math.min(60, t.length())))
                  .distinct()
                  .limit(3)
                  .toList();
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (transitions.size() > 1) {
            boolean verification =
                groups.stream().allMatch(g -> workCategory(g.facts().category()))
                    && groups.stream().anyMatch(g -> development(g.facts().category()))
                    && groups.stream()
                        .anyMatch(
                            g ->
                                (g.facts().content() + " " + g.facts().title())
                                    .matches("(?i).*(テスト|test|maven).*"));
            text =
                project
                    + " の「"
                    + String.join(" → ", transitions)
                    + "」関連画面を行き来"
                    + (verification ? "し、実装・検証を進めていた可能性があります。" : "する作業の可能性があります。");
          }
        }
        var supporting =
            groups.stream()
                .flatMap(g -> g.facts().desktop().stream())
                .filter(
                    a ->
                        ActivityRolePolicy.normalize(a.projectCandidate())
                            .equals(ActivityRolePolicy.normalize(f.project())))
                .filter(
                    a ->
                        Set.of("research", "development", "documentation", "coding")
                            .contains(a.type()))
                .map(ActivityRecord.Activity::contentTitle)
                .filter(t -> !t.isBlank())
                .map(t -> t.substring(0, Math.min(60, t.length())))
                .distinct()
                .limit(2)
                .toList();
        if (!supporting.isEmpty())
          text += " 補助画面には「" + String.join(" / ", supporting) + "」が表示されていた可能性があります（操作の証拠ではありません）。";
        confidence = strict ? .6 : .55;
        status = "INFERRED";
      }
    }
    if (!executions.isEmpty()) text += " れいの自動実行記録があります（ユーザー操作や成功を示すものではありません）。";
    return new WorkActivityInference(
        UUID.randomUUID().toString(),
        fingerprint,
        windowEnd,
        windowStart,
        windowEnd,
        projectId,
        project,
        text,
        observationIds,
        executions,
        confidence,
        status,
        "RULES",
        0,
        0);
  }

  /** 作業状況の把握において、augmentedに該当するか判定する。 */
  private boolean augmented() {
    return groups.stream()
        .anyMatch(g -> g.facts().input() != null || !g.facts().desktop().isEmpty());
  }

  /** 作業状況の把握において、同一プロジェクトに該当するか判定する。 */
  private static boolean sameProject(Facts f) {
    return !f.project().isBlank()
        && ActivityRolePolicy.normalize(f.project())
            .equals(ActivityRolePolicy.normalize(f.candidate()));
  }

  /** 作業状況の把握において、developmentに該当するか判定する。 */
  private static boolean development(String category) {
    return Set.of("development", "coding").contains(category);
  }

  /** 作業状況の把握において、作業分類に該当するか判定する。 */
  private static boolean workCategory(String category) {
    return Set.of("development", "coding", "research", "documentation").contains(category);
  }

  /** 作業状況の把握において、継続に該当するか判定する。 */
  private boolean continuous() {
    if (!augmented()) return true;
    for (int i = 0; i < groups.size(); i++) {
      var current = groups.get(i);
      if (current.facts().input() != null
          && current.facts().input().reliable()
          && !current.facts().input().recent()) return false;
      if (i > 0
          && (!Objects.equals(current.facts().continuity(), groups.get(i - 1).facts().continuity())
              || Duration.between(groups.get(i - 1).last(), current.first()).getSeconds() > 90))
        return false;
    }
    return true;
  }

  /** 作業状況の把握で用いる項目に整理した内容入力を返す。 */
  public String structuredInput(int maximum) {
    try {
      String input =
          JSON.writeValueAsString(
              Map.of(
                  "windowStart",
                  windowStart,
                  "windowEnd",
                  windowEnd,
                  "observations",
                  groups,
                  "agentExecutions",
                  executions,
                  "desktopMeaning",
                  "Supplemental INFERENCE only; visible does not mean used. Foreground determines primary activity; never infer a goal from background entertainment."));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (input.length() > maximum)
        throw new IllegalArgumentException("Temporal input exceeds character budget");
      return input;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Temporal input encoding failed", e);
    }
  }

  /** 作業状況の把握で用いる変更なしを返す。 */
  static String clean(String value) {
    String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}\\s]+", " ").strip();
    text = text.substring(0, Math.min(160, text.length()));
    return new dev.mikoto2000.rei.memory.util.SensitiveInfoDetector().containsSensitiveInfo(text)
        ? "[redacted]"
        : text;
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(Object value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Temporal evidence fingerprint failed", e);
    }
  }
}
