package dev.mikoto2000.rei.memory.command;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.LongTermMemory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.SleepService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Component;

/**
 * 記憶コマンドで共通に利用する補助処理。
 *
 * <p>Read-only reporting and explicit archive; project ownership always comes from the selected
 * client.
 */
@Component
public class MemoryCommandSupport {
  /** 保存と検索の窓口。 */
  private final MemoryList listMemories;

  private final MemorySearch searchMemories;
  private final Function<String, Optional<LongTermMemory>> findMemory;
  private final Function<String, List<String>> memoryRelations;
  private final BiFunction<String, String, Optional<String>> reflectionProof;
  private final Consumer<String> archiveMemory;
  private final BiFunction<String, Integer, List<dev.mikoto2000.rei.memory.service.SleepRun>>
      sleepHistory;
  private final Function<String, Map<String, Long>> memoryCounts;

  @FunctionalInterface
  private interface MemoryList {
    List<LongTermMemory> list(String project, int limit, int offset);
  }

  @FunctionalInterface
  private interface MemorySearch {
    List<LongTermMemory> search(String query, String project, int limit);
  }

  /** 待機。 */
  private final Supplier<List<MemoryRepository.AutoSleepRequest>> pendingSleepRequests;

  private final Predicate<MemoryRepository.AutoSleepRequest> completeSleepRequest;
  private final ToLongFunction<String> unsleptTurns;

  /** プロジェクト一覧。 */
  private final java.util.function.Supplier<String> currentProjectId;

  private final java.util.function.Supplier<String> currentSessionId;

  /** この機能の動作設定。 */
  private final MemoryProperties properties;

  /** 指定された値や利用先を使って、MemoryCommandSupportを初期化する。 */
  public MemoryCommandSupport(
      MemoryRepository repository,
      SleepService sleep,
      ProjectService projects,
      MemoryProperties properties) {
    this.listMemories = (project, limit, offset) -> repository.list(project, limit, offset);
    this.searchMemories = (query, project, limit) -> repository.search(query, project, limit);
    this.findMemory = id -> repository.find(id);
    this.memoryRelations = id -> repository.relations(id);
    this.reflectionProof =
        (project, id) ->
            repository.verifiedReflectionProofByMemory(project, id).map(Object::toString);
    this.archiveMemory = id -> repository.archive(id);
    this.sleepHistory = (project, limit) -> repository.history(project, limit);
    this.memoryCounts = project -> repository.counts(project);
    this.pendingSleepRequests = () -> sleep.pendingAutoSleepRequests();
    this.completeSleepRequest = request -> sleep.completeAutoSleepRequest(request);
    this.unsleptTurns = session -> sleep.unsleptTurns(session);
    this.currentProjectId = () -> projects.currentContext().id();
    this.currentSessionId = () -> projects.currentSessionId();
    this.properties = properties;
  }

  /** 長期記憶の活用で用いるプロジェクトを返す。 */
  private String project() {
    return currentProjectId.get();
  }

  /** この機能を利用する設定が有効かどうかを返す。 */
  public void enabled() {
    if (!properties.enabled())
      throw new IllegalStateException("Memory is disabled (rei.memory.enabled=false)");
  }

  /** 長期記憶の活用で用いる自動待機要求一覧を返す。 */
  public String autoSleepRequests() {
    return autoSleepRequestsForProject(project());
  }

  /** 長期記憶の活用で用いる自動待機要求一覧対象プロジェクトを返す。 */
  public String autoSleepRequestsForProject(String project) {
    var format = new dev.mikoto2000.rei.conversation.HistoryFormatter();
    var rows =
        pendingSleepRequests.get().stream()
            .filter(r -> r.projectId().equals(project))
            .map(
                r ->
                    format.label(r.sessionId())
                        + " revision="
                        + r.revision()
                        + " cause="
                        + format.label(r.cause()))
            .toList();
    return rows.isEmpty()
        ? "No pending Auto Sleep requests."
        : dev.mikoto2000.rei.event.CredentialRedactor.redact(String.join("\n", rows));
  }

  /** 利用者が不要とした実行を止めるため、自動待機要求をキャンセルする。 */
  public String cancelAutoSleepRequest(String session, long revision) {
    return cancelAutoSleepRequestForProject(project(), session, revision);
  }

  /** 利用者が不要とした実行を止めるため、自動待機要求対象プロジェクトをキャンセルする。 */
  public String cancelAutoSleepRequestForProject(String project, String session, long revision) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (session == null || session.isBlank() || session.length() > 512 || revision < 1)
      throw new IllegalArgumentException("Session and positive revision are required");
    if (!completeSleepRequest.test(
        new MemoryRepository.AutoSleepRequest(session, project, revision, "")))
      throw new IllegalStateException("Auto Sleep request changed or is not in this Project");
    return "Pending Auto Sleep request cancelled. An already running Sleep is unaffected.";
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public String list(int limit, int offset) {
    return listForProject(project(), limit, offset);
  }

  /** 利用可能な対象を確認できるよう、対象プロジェクトを一覧にする。 */
  public String listForProject(String project, int limit, int offset) {
    enabled();
    return render(listMemories.list(project, limit, offset));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public String search(String query, int limit) {
    return searchForProject(project(), query, limit);
  }

  /** 依頼に関連する情報を得るため、対象プロジェクトを検索する。 */
  public String searchForProject(String project, String query, int limit) {
    enabled();
    return render(searchMemories.search(query, project, limit));
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  public String show(String id) {
    return showForProject(project(), id);
  }

  /** 利用者が内容を確認できるよう、対象プロジェクトを表示する。 */
  public String showForProject(String project, String id) {
    enabled();
    var m = visible(project, id);
    return "id: "
        + m.id()
        + "\nscope: "
        + m.scope()
        + "\nprojectId: "
        + m.projectId()
        + "\ntype: "
        + m.type()
        + "\nstatus: "
        + m.status()
        + "\ncontent: "
        + m.content()
        + "\nsummary: "
        + m.summary()
        + "\nconfidence: "
        + m.confidence()
        + "\nimportance: "
        + m.importance()
        + "\ncreatedAt: "
        + m.createdAt()
        + "\nupdatedAt: "
        + m.updatedAt()
        + "\nlastAccessedAt: "
        + m.lastAccessedAt()
        + "\nvalidFrom: "
        + m.validFrom()
        + "\nvalidUntil: "
        + m.validUntil()
        + "\nsupersededBy: "
        + m.supersededBy()
        + "\nsource sessions/turns: "
        + m.sources()
        + "\ntags: "
        + m.tags()
        + "\nrelations: "
        + memoryRelations.apply(id)
        + reflectionProof
            .apply(project, id)
            .map(proof -> "\nverified reflection proof: " + proof)
            .orElse("");
  }

  /** 長期記憶の活用で用いる記憶の削除を返す。 */
  public String forget(String id) {
    return forgetForProject(project(), id);
  }

  /** 長期記憶の活用で用いる記憶の削除対象プロジェクトを返す。 */
  public String forgetForProject(String project, String id) {
    enabled();
    visible(project, id);
    archiveMemory.accept(id);
    return "Archived: " + id;
  }

  /** 長期記憶の活用で用いる表示対象を返す。 */
  private LongTermMemory visible(String project, String id) {
    return findMemory
        .apply(id)
        .filter(m -> m.scope() == MemoryScope.GLOBAL || Objects.equals(m.projectId(), project))
        .orElseThrow(
            () -> new IllegalArgumentException("Memory not found in GLOBAL/current project"));
  }

  /** 利用者が内容を確認できるよう、表示形式へ変換する。 */
  private String render(List<LongTermMemory> memories) {
    if (memories.isEmpty()) return "No memories.";
    return String.join(
        "\n\n",
        memories.stream()
            .map(
                m ->
                    m.id()
                        + " ["
                        + m.scope()
                        + "] ["
                        + m.type()
                        + "] "
                        + m.status()
                        + "\n"
                        + m.content())
            .toList());
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public String status() {
    return statusForProject(project(), currentSessionId.get());
  }

  /** 長期記憶の活用で用いる状態対象プロジェクトを返す。 */
  public String statusForProject(String project, String session) {
    enabled();
    var runs = sleepHistory.apply(project, 1);
    StringBuilder text = new StringBuilder("Memory Status\n");
    memoryCounts
        .apply(project)
        .forEach((key, count) -> text.append(key).append(": ").append(count).append('\n'));
    text.append("Last sleep: ").append(runs.isEmpty() ? "none" : runs.getFirst()).append('\n');
    text.append("unslept turns: ").append(unsleptTurns.applyAsLong(session));
    return text.toString();
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  public String history() {
    return historyForProject(project());
  }

  /** 長期記憶の活用で用いる履歴対象プロジェクトを返す。 */
  public String historyForProject(String project) {
    enabled();
    var runs = sleepHistory.apply(project, 100);
    return "Sleep history\n"
        + (runs.isEmpty()
            ? "none"
            : String.join("\n", runs.stream().map(Object::toString).toList()));
  }

  /** 長期記憶の活用で用いるレポートを返す。 */
  public String report(SleepService.Report report) {
    var r = report.run();
    StringBuilder text =
        new StringBuilder(report.preview() ? "Sleep Preview\n" : "Sleep completed.\n");
    text.append("Processed turns: ")
        .append(r.processedTurns())
        .append("\nMemory candidates: ")
        .append(r.candidateCount())
        .append("\nAdded: ")
        .append(r.added())
        .append("\nUpdated: ")
        .append(r.updated())
        .append("\nMerged: ")
        .append(r.merged())
        .append("\nSuperseded: ")
        .append(r.superseded())
        .append("\nIgnored (including duplicates): ")
        .append(r.ignored())
        .append("\nConflicts: ")
        .append(r.conflicts())
        .append("\nFailed: ")
        .append(r.failed())
        .append('\n');
    for (var plan : report.plans()) {
      var c = plan.candidate();
      text.append("\n[")
          .append(c.scope())
          .append("] [")
          .append(c.type())
          .append("]\nconfidence: ")
          .append(c.confidence())
          .append(" importance: ")
          .append(c.importance())
          .append('\n')
          .append(c.content())
          .append("\nAction: ")
          .append(plan.resolution().action())
          .append('\n');
      for (var target : plan.targets())
        text.append("Existing: ")
            .append(target.id())
            .append(" ")
            .append(target.content())
            .append('\n');
    }
    return text.toString();
  }
}
