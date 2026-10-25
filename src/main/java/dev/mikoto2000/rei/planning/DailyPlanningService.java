package dev.mikoto2000.rei.planning;

import dev.mikoto2000.rei.activity.ActivityWorkContextService;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.task.TaskManagerService;
import dev.mikoto2000.rei.application.task.TaskView;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.workcontext.WorkContext;
import dev.mikoto2000.rei.workcontext.WorkContextRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 日次計画のユースケースを実現する。
 *
 * <p>Deterministic read projection. No extraction, scheduling, reconciliation, or model invocation.
 */
public final class DailyPlanningService implements dev.mikoto2000.rei.secretary.SecretaryContext {
  @Override
  public List<dev.mikoto2000.rei.secretary.SecretaryContext.Item> context(
      String project, LocalDate date, ZoneId zone) {
    if (!date.equals(clock.instant().atZone(zone).toLocalDate())) return List.of();
    return today(List.of(project)).projects().stream()
        .flatMap(p -> p.items().stream())
        .limit(256)
        .map(
            item ->
                new dev.mikoto2000.rei.secretary.SecretaryContext.Item(
                    item.source(), item.id(), item.title(), item.status(), item.reason()))
        .toList();
  }

  /** 分類一覧。 */
  public static final List<String> CATEGORIES =
      List.of("Today", "Overdue", "Waiting", "Blocked", "Scheduled", "Suggested Next");

  /**
   * 日々の計画の支援で受け渡す計画を表す。
   *
   * @param schemaVersion データ形式形式のバージョン
   * @param date 日付
   * @param zone タイムゾーン
   * @param generatedAt 生成済み日時
   * @param method メソッド
   * @param categories 分類一覧
   * @param projects プロジェクト一覧
   * @param partial 部分的な結果
   */
  public record Plan(
      int schemaVersion,
      LocalDate date,
      String zone,
      Instant generatedAt,
      String method,
      List<String> categories,
      List<ProjectPlan> projects,
      boolean partial) {
    /** 受け取った情報からPlanを作成する。 */
    public Plan {
      categories = List.copyOf(categories);
      projects = List.copyOf(projects);
    }
  }

  /**
   * 日々の計画の支援で受け渡すプロジェクト計画を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param name 名前
   * @param items 項目一覧
   * @param activity 作業状況
   * @param partial 部分的な結果
   * @param warnings 注意事項
   */
  public record ProjectPlan(
      String projectId,
      String name,
      List<Item> items,
      ActivitySummary activity,
      boolean partial,
      List<String> warnings) {
    /** 受け取った情報からProjectPlanを作成する。 */
    public ProjectPlan {
      items = List.copyOf(items);
      warnings = List.copyOf(warnings);
    }
  }

  /**
   * 日々の計画の支援で受け渡す項目を表す。
   *
   * @param id 識別子
   * @param source 取得元
   * @param title タイトル
   * @param reason 理由
   * @param status 状態
   * @param sessionId 会話の識別子
   * @param updatedAt 更新日時
   * @param dueAt 期限日時
   * @param categories 分類一覧
   * @param tags タグ一覧
   * @param certainty 確かさ
   * @param references 参照先一覧
   */
  public record Item(
      String id,
      String source,
      String title,
      String reason,
      String status,
      String sessionId,
      Instant updatedAt,
      Instant dueAt,
      List<String> categories,
      List<String> tags,
      String certainty,
      List<TaskView.Reference> references) {
    /** 受け取った情報からItemを作成する。 */
    public Item {
      categories = List.copyOf(categories);
      tags = List.copyOf(tags);
      references = List.copyOf(references);
    }
  }

  /**
   * 日々の計画の支援で受け渡す観測内容を表す。
   *
   * @param recordId 記録識別子
   * @param capturedAt 観測日時
   * @param itemIds 項目識別子一覧
   * @param basis 判断の根拠
   */
  public record Observation(
      String recordId, Instant capturedAt, List<String> itemIds, String basis) {
    /** 受け取った情報からObservationを作成する。 */
    public Observation {
      itemIds = List.copyOf(itemIds);
    }
  }

  /**
   * 日々の計画の支援で受け渡す作業状況要約を表す。
   *
   * @param date 日付
   * @param zone タイムゾーン
   * @param provenance 情報の来歴
   * @param linkedObservations linked観測内容一覧
   * @param partial 部分的な結果
   * @param missingContextRecords 未指定文脈記録一覧
   * @param references 参照先一覧
   */
  public record ActivitySummary(
      LocalDate date,
      String zone,
      String provenance,
      int linkedObservations,
      boolean partial,
      int missingContextRecords,
      List<Observation> references) {
    /** 受け取った情報からActivitySummaryを作成する。 */
    public ActivitySummary {
      references = List.copyOf(references);
    }
  }

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** タイムゾーン。 */
  private final ZoneId zone;

  /** タスク上限／項目上限／予算秒数。 */
  private final int taskLimit, itemLimit, budgetSeconds;

  /** 古い状態変更後。 */
  private final Duration staleAfter;

  /** プロジェクト一覧。 */
  private final ProjectRegistry projects;

  /** 許可。 */
  private final List<ProjectContext> allowed;

  /** タスク一覧。 */
  private final TaskManagerService tasks;

  /** 文脈一覧。 */
  private final WorkContextRepository contexts;

  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 依存条件一覧。 */
  private final PersistentDependencyRepository dependencies;

  /** 予約されたエージェント実行を管理する窓口。 */
  private final PersistentAgentScheduler schedules;

  /** 再開用の記録一覧。 */
  private final PersistentCheckpointRepository checkpoints;

  /** 作業状況。 */
  private final ActivityWorkContextService activity;

  /** 指定された値や利用先を使って、DailyPlanningServiceを初期化する。 */
  public DailyPlanningService(
      DailyPlanningProperties properties,
      Clock clock,
      ProjectRegistry projects,
      TaskManagerService tasks,
      WorkContextRepository contexts,
      GoalRepository goals,
      PersistentDependencyRepository dependencies,
      PersistentAgentScheduler schedules,
      PersistentCheckpointRepository checkpoints,
      ActivityWorkContextService activity) {
    properties.validate();
    this.clock = clock;
    this.zone = ZoneId.of(properties.getZone());
    this.projects = projects;
    this.tasks = tasks;
    this.contexts = contexts;
    this.goals = goals;
    this.dependencies = dependencies;
    this.schedules = schedules;
    this.checkpoints = checkpoints;
    this.activity = activity;
    taskLimit = properties.getMaxTasksPerProject();
    itemLimit = properties.getMaxItemsPerProject();
    budgetSeconds = properties.getCollectionBudgetSeconds();
    staleAfter = properties.getStaleAfter();
    allowed =
        properties.getProjects().stream()
            .sorted()
            .map(
                id ->
                    projects
                        .resolveById(id)
                        .orElseThrow(
                            () -> new IllegalArgumentException("Today Project not registered")))
            .toList();
  }

  /** 日々の計画の支援で用いる今日を返す。 */
  public Plan today(List<String> requested) {
    var selected = requested == null ? allowed : selection(requested);
    for (var owner : selected) verifyRoot(owner);
    Instant now = clock.instant();
    LocalDate date = now.atZone(zone).toLocalDate();
    Instant start = date.atStartOfDay(zone).toInstant(),
        end = date.plusDays(1).atStartOfDay(zone).toInstant();
    long deadline = System.nanoTime() + Duration.ofSeconds(budgetSeconds).toNanos();
    var results = new ArrayList<ProjectPlan>();
    for (var owner : selected) {
      var warnings = new LinkedHashSet<String>();
      var items = new ArrayList<Item>();
      int scanned = 0;
      String cursor = null;
      do {
        if (expired(deadline)) {
          warnings.add("collection_budget_reached");
          break;
        }
        var page = tasks.list(owner.id(), null, Math.min(100, taskLimit - scanned), cursor);
        for (var task : page.items()) {
          scanned++;
          if (Set.of("COMPLETED", "CANCELLED").contains(task.status())) continue;
          if (items.size() == itemLimit) {
            warnings.add("item_limit_reached");
            break;
          }
          var item = task(owner, task, now, start, end);
          if (item != null) {
            items.add(item);
            if (item.tags().contains("REFERENCES_PARTIAL")) warnings.add("task_references_partial");
          }
        }
        cursor = page.nextCursor();
        if (scanned == taskLimit && cursor != null) {
          warnings.add("task_limit_reached");
          break;
        }
        if (warnings.contains("item_limit_reached")) break;
      } while (cursor != null);
      if (contexts == null) warnings.add("work_context_unavailable");
      else if (expired(deadline)) warnings.add("collection_budget_reached");
      else
        contexts
            .current(owner.id())
            .ifPresent(
                saved -> {
                  if (!owner.id().equals(saved.projectId())
                      || saved.git() == null
                      || !sameRoot(owner, saved.git().directory())) {
                    warnings.add("work_context_root_unverified");
                    return;
                  }
                  for (var item : saved.items()) {
                    if (Set.of(
                                WorkContext.Status.COMPLETED,
                                WorkContext.Status.WITHDRAWN,
                                WorkContext.Status.SUPERSEDED)
                            .contains(item.status())
                        || !Set.of(
                                WorkContext.Kind.PURPOSE,
                                WorkContext.Kind.CURRENT_WORK,
                                WorkContext.Kind.PENDING,
                                WorkContext.Kind.BLOCKER,
                                WorkContext.Kind.NEXT_ACTION,
                                WorkContext.Kind.VERIFICATION)
                            .contains(item.kind())) continue;
                    if (items.size() == itemLimit) {
                      warnings.add("item_limit_reached");
                      break;
                    }
                    var categories = new LinkedHashSet<String>();
                    var tags = new ArrayList<String>();
                    tags.add(item.kind().name());
                    if (item.kind() == WorkContext.Kind.BLOCKER) categories.add("Blocked");
                    else if (item.kind() == WorkContext.Kind.NEXT_ACTION
                        || item.kind() == WorkContext.Kind.VERIFICATION)
                      categories.add("Suggested Next");
                    else categories.add("Today");
                    if (item.updatedAt() != null
                        && item.updatedAt().isBefore(now.minus(staleAfter))) tags.add("STALE");
                    items.add(
                        new Item(
                            "work-context:" + item.id(),
                            "WORK_CONTEXT",
                            safe(item.text(), 512),
                            safe(item.reason(), 512),
                            item.status().name(),
                            null,
                            item.updatedAt(),
                            null,
                            List.copyOf(categories),
                            tags,
                            item.certainty().name(),
                            List.of(
                                new TaskView.Reference(
                                    "WORK_CONTEXT_REVISION", Long.toString(saved.revision())))));
                  }
                });
      ActivitySummary observation = null;
      if (activity != null && !expired(deadline)) {
        try {
          var saved = activity.observationLinksBounded(owner.id(), "today");
          var refs =
              saved.links().stream()
                  .map(
                      link ->
                          new Observation(
                              safe(link.recordId(), 128),
                              link.capturedAt(),
                              link.itemIds().stream().limit(20).map(id -> safe(id, 128)).toList(),
                              "OBSERVATION_CONTEXT"))
                  .toList();
          observation =
              new ActivitySummary(
                  saved.date(),
                  saved.zone(),
                  "OBSERVATION_ONLY",
                  refs.size(),
                  saved.partial(),
                  saved.missingContextRecords(),
                  refs);
          if (saved.partial() || saved.missingContextRecords() > 0)
            warnings.add("activity_evidence_partial");
        } catch (dev.mikoto2000.rei.activity.ActivityQueryLimitException
            | UnsupportedOperationException unavailable) {
          warnings.add("activity_evidence_unavailable");
        }
      } else if (activity != null) warnings.add("collection_budget_reached");
      verifyRoot(owner);
      items.sort(
          Comparator.comparing((Item item) -> item.dueAt() == null ? Instant.MAX : item.dueAt())
              .thenComparing(Item::id));
      results.add(
          new ProjectPlan(
              owner.id(),
              safe(owner.name(), 128),
              items,
              observation,
              warnings.stream().anyMatch(this::partial),
              List.copyOf(warnings)));
    }
    return new Plan(
        1,
        date,
        zone.getId(),
        now,
        "DETERMINISTIC",
        CATEGORIES,
        results,
        results.stream().anyMatch(ProjectPlan::partial));
  }

  /** 日々の計画の支援において、部分的な結果に該当するか判定する。 */
  private boolean partial(String warning) {
    return Set.of(
            "collection_budget_reached",
            "task_limit_reached",
            "item_limit_reached",
            "task_references_partial",
            "work_context_root_unverified",
            "activity_evidence_partial",
            "activity_evidence_unavailable")
        .contains(warning);
  }

  /** 日々の計画の支援で用いるタスクを返す。 */
  private Item task(ProjectContext owner, TaskView task, Instant now, Instant start, Instant end) {
    String title = task.kind() + " " + task.sourceId();
    Instant due = null;
    var tags = new ArrayList<String>();
    var refs = new ArrayList<>(task.results());
    if (task.kind().equals("GOAL") && goals != null) {
      var saved = goals.get(owner.id(), task.sourceId());
      if (!sameRoot(owner, saved.projectRoot())) return null;
      title = saved.objective();
      tags.add("CURRENT_GOAL");
    }
    if (task.kind().equals("DEPENDENCY") && dependencies != null) {
      var saved = dependencies.get(owner.id(), task.sourceId());
      if (!sameRoot(owner, saved.projectRoot())) return null;
      title = saved.spec().kind() + ": " + saved.spec().target();
      due = saved.deadline();
    }
    if (task.kind().equals("SCHEDULE") && schedules != null) {
      var saved = schedules.get(owner.id(), task.sourceId());
      if (!sameRoot(owner, saved.projectRoot())) return null;
      title = saved.task().action();
      due =
          task.status().equals("WAITING")
              ? schedules
                  .eventTrigger(owner.id(), task.sourceId())
                  .map(PersistentAgentScheduler.EventTrigger::expiresAt)
                  .orElse(null)
              : saved.task().executeAt();
      tags.add("SAVED_CONTINUATION");
      if (saved.status().equals("PENDING")) tags.add("ACTIVATION_REQUIRED");
    }
    if (task.kind().equals("CHECKPOINT") && checkpoints != null) {
      var saved = checkpoints.get(owner.id(), task.sourceId());
      if (!sameRoot(owner, saved.projectRoot())) return null;
      title = saved.nextAction() == null ? saved.request() : saved.nextAction();
      if (saved.resumedFromRunId() != null) tags.add("RESUMED");
    }
    if (task.updatedAt() != null && task.updatedAt().isBefore(now.minus(staleAfter)))
      tags.add("STALE");
    var categories = new LinkedHashSet<String>();
    if (task.status().equals("WAITING")) categories.add("Waiting");
    if (Set.of("BLOCKED", "FAILED", "UNKNOWN").contains(task.status())) categories.add("Blocked");
    if (task.kind().equals("SCHEDULE")) categories.add("Scheduled");
    if (due != null && due.isBefore(now)) categories.add("Overdue");
    if (due != null && !due.isBefore(start) && due.isBefore(end) || categories.isEmpty())
      categories.add("Today");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (refs.size() > 32) tags.add("REFERENCES_PARTIAL");
    return new Item(
        task.id(),
        task.kind(),
        safe(title, 512),
        safe(
                Objects.toString(task.waitingReason(), "")
                    + " "
                    + Objects.toString(task.errorSummary(), ""),
                512)
            .strip(),
        task.status(),
        task.sessionId(),
        task.updatedAt(),
        due,
        List.copyOf(categories),
        tags,
        "SAVED_EXECUTION_STATE",
        refs.stream().limit(32).toList());
  }

  /** 日々の計画の支援で用いる選択内容を返す。 */
  private List<ProjectContext> selection(List<String> requested) {
    if (requested.isEmpty()
        || requested.size() > 16
        || requested.stream().anyMatch(Objects::isNull)
        || new HashSet<>(requested).size() != requested.size())
      throw new IllegalArgumentException("Invalid Today selection");
    return requested.stream()
        .sorted()
        .map(
            id ->
                allowed.stream()
                    .filter(project -> project.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Today Project")))
        .toList();
  }

  /** 要求された条件を満たすか判断するため、作業ルートを確認する。 */
  private void verifyRoot(ProjectContext owner) {
    if (projects
        .resolveById(owner.id())
        .filter(current -> current.root().equals(owner.root()))
        .isEmpty()) throw new ResourceNotFoundException("Today Project");
  }

  /** 日々の計画の支援において、同一作業ルートに該当するか判定する。 */
  private static boolean sameRoot(ProjectContext owner, String root) {
    try {
      return root != null && owner.root().equals(Path.of(root).toAbsolutePath().normalize());
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  /** 日々の計画の支援において、期限切れに該当するか判定する。 */
  private static boolean expired(long deadline) {
    return System.nanoTime() - deadline >= 0;
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  private static String safe(String value, int limit) {
    var text = CredentialRedactor.redact(Objects.toString(value, ""));
    return text.substring(0, Math.min(limit, text.length()));
  }
}
