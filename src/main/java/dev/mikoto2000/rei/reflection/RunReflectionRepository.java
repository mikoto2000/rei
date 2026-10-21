package dev.mikoto2000.rei.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.TaskCreatedPayload;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 実行振り返りを保存・検索するための窓口。
 *
 * <p>Durable facts and review candidates; candidates are not automatically promoted to trusted
 * memories.
 */
@Repository
public class RunReflectionRepository {
  /**
   * 作業の振り返りで受け渡す操作を表す。
   *
   * @param callId 呼び出し識別子
   * @param tool ツール
   * @param status 状態
   * @param errorType エラー種別
   */
  public record Action(String callId, String tool, String status, String errorType) {}

  /**
   * 作業の振り返りで受け渡す項目を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param runId 実行の識別子
   * @param sourceKind 取得元種類
   * @param sourceId 取得元識別子
   * @param status 状態
   * @param expected 期待する内容
   * @param actions 操作一覧
   * @param actionsTruncated 操作一覧省略の有無
   * @param failureReason 失敗情報理由
   * @param gap 間隔
   * @param nextAction 次操作
   * @param knowledgeCandidates 知識候補一覧
   * @param sourceEvent 取得元イベント
   * @param createdAt 作成日時
   */
  public record Item(
      String id,
      String projectId,
      String sessionId,
      String runId,
      String sourceKind,
      String sourceId,
      String status,
      String expected,
      List<Action> actions,
      boolean actionsTruncated,
      String failureReason,
      String gap,
      String nextAction,
      List<String> knowledgeCandidates,
      String sourceEvent,
      Instant createdAt) {
    /** 受け取った情報からItemを作成する。 */
    public Item {
      actions = List.copyOf(actions);
      knowledgeCandidates = List.copyOf(knowledgeCandidates);
    }
  }

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  /** 指定された値や利用先を使って、RunReflectionRepositoryを初期化する。 */
  public RunReflectionRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS run_reflection_actions(project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,call_id TEXT NOT NULL,tool TEXT NOT NULL,status TEXT NOT NULL,error_type TEXT NOT NULL,PRIMARY KEY(project,session,run,call_id,status))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS run_reflection_tasks(project TEXT NOT NULL,session TEXT NOT NULL,task TEXT NOT NULL,title TEXT NOT NULL,PRIMARY KEY(project,session,task))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS run_reflections(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,document TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,session,kind,source,status))")
        .update();
    db.sql("CREATE INDEX IF NOT EXISTS run_reflections_project ON run_reflections(project,created)")
        .update();
  }

  /** 作業の振り返りに関する操作を処理する。 */
  void action(AgentEvent event, Action action) {
    if (event.runId() == null || !valid(action.callId()) || !valid(action.tool())) return;
    // Keep one extra row to prove truncation. Further calls do not grow a Run without bound.
    db.sql(
            "INSERT OR IGNORE INTO run_reflection_actions SELECT ?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM run_reflection_actions WHERE project=? AND session=? AND run=?)<257")
        .params(
            event.projectId(),
            event.sessionId(),
            event.runId(),
            action.callId(),
            safe(action.tool(), 128),
            action.status(),
            safe(action.errorType(), 128),
            event.projectId(),
            event.sessionId(),
            event.runId())
        .update();
  }

  /** 作業の振り返りに関するタスクを処理する。 */
  void task(AgentEvent event, TaskCreatedPayload task) {
    if (!valid(task.taskId())) return;
    db.sql("INSERT OR IGNORE INTO run_reflection_tasks VALUES(?,?,?,?)")
        .params(event.projectId(), event.sessionId(), task.taskId(), safe(task.title(), 512))
        .update();
  }

  /** 作業の振り返りで用いるreflectを返す。 */
  Item reflect(AgentEvent event, String kind, String source, String status, String failure) {
    if (!valid(source)) throw new IllegalArgumentException("Reflection source identity required");
    List<Action> observed =
        !kind.equals("RUN") || event.runId() == null
            ? List.of()
            : db.sql(
                    "SELECT * FROM run_reflection_actions WHERE project=? AND session=? AND run=? ORDER BY rowid LIMIT 257")
                .params(event.projectId(), event.sessionId(), event.runId())
                .query(
                    (rs, n) ->
                        new Action(
                            rs.getString("call_id"),
                            rs.getString("tool"),
                            rs.getString("status"),
                            rs.getString("error_type")))
                .list();
    boolean truncated = observed.size() > 256;
    List<Action> actions = observed.stream().limit(256).toList();
    String expected =
        kind.equals("TASK")
            ? db.sql(
                    "SELECT title FROM run_reflection_tasks WHERE project=? AND session=? AND task=?")
                .params(event.projectId(), event.sessionId(), source)
                .query(String.class)
                .optional()
                .orElse("TASK_COMPLETION_REQUESTED")
            : "TASK_CRITERIA_NOT_DECLARED";
    boolean permission =
        actions.stream()
            .anyMatch(
                a -> Set.of("PermissionRequired", "PermissionDenied").contains(a.errorType()));
    boolean toolFailed = actions.stream().anyMatch(a -> a.status().equals("FAILED"));
    String gap =
        status.equals("COMPLETED")
            ? (kind.equals("TASK") ? "TASK_COMPLETION_REPORTED" : "TASK_CRITERIA_NOT_DECLARED")
            : status.equals("CANCELLED") ? "EXECUTION_CANCELLED" : "EXECUTION_FAILED";
    String next =
        permission
            ? "REVIEW_APPROVAL_BEFORE_RETRY"
            : !status.equals("COMPLETED")
                ? "INSPECT_FAILURE_AND_RECONCILE_BEFORE_RETRY"
                : toolFailed
                    ? "REVIEW_FAILED_TOOLS_AND_VERIFY_CRITERIA"
                    : "INSPECT_RESULT_AND_VERIFY_CRITERIA";
    var knowledge = new ArrayList<String>();
    if (permission)
      knowledge.add(
          "Review the required capability and explicit approval before retrying this task; an inbox acknowledgement is not permission.");
    if (toolFailed)
      knowledge.add(
          "A Tool failed in this execution. Preserve its call identity and inspect the recorded failure before choosing a retry.");
    actions.stream()
        .filter(a -> a.status().equals("FAILED"))
        .limit(8)
        .forEach(
            a ->
                knowledge.add(
                    "Recorded failed Tool "
                        + safe(a.tool(), 128)
                        + " (call "
                        + safe(a.callId(), 128)
                        + ", error type "
                        + safe(a.errorType(), 128)
                        + "). Inspect this specific operation before selecting a future retry."));
    if (!status.equals("COMPLETED"))
      knowledge.add(
          "Recorded "
              + status
              + " for "
              + kind
              + " "
              + safe(source, 128)
              + " with error type "
              + safe(failure, 128)
              + ". Reconcile this source before reusing its execution result.");
    if (status.equals("COMPLETED"))
      knowledge.add(
          "Normal execution termination or a reported Task completion does not replace independent verification of the requested result.");
    if (truncated)
      knowledge.add(
          "The action record exceeded 256 facts; review the full Project event history before drawing a complete conclusion.");
    var item =
        new Item(
            "run-reflection-" + UUID.randomUUID(),
            event.projectId(),
            event.sessionId(),
            event.runId(),
            kind,
            source,
            status,
            expected,
            actions,
            truncated,
            safe(failure, 128),
            gap,
            next,
            knowledge,
            event.id(),
            clock.instant());
    db.sql("INSERT OR IGNORE INTO run_reflections VALUES(?,?,?,?,?,?,?,?)")
        .params(
            item.id(),
            item.projectId(),
            item.sessionId(),
            kind,
            source,
            status,
            json(item),
            clock.millis())
        .update();
    return read(
        db.sql(
                "SELECT document FROM run_reflections WHERE project=? AND session=? AND kind=? AND source=? AND status=?")
            .params(item.projectId(), item.sessionId(), kind, source, status)
            .query(String.class)
            .single());
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Item> list(String project) {
    return db
        .sql(
            "SELECT document FROM run_reflections WHERE project=? ORDER BY created DESC,id LIMIT 256")
        .param(project)
        .query(String.class)
        .list()
        .stream()
        .map(this::read)
        .toList();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Item get(String project, String id) {
    return db.sql("SELECT document FROM run_reflections WHERE project=? AND id=?")
        .params(project, id)
        .query(String.class)
        .optional()
        .map(this::read)
        .orElseThrow(
            () -> new IllegalArgumentException("Run reflection not found in this Project"));
  }

  /** 作業の振り返りで用いる保存・受け渡しに使うJSON変換を返す。 */
  private String json(Item item) {
    try {
      return mapper.writeValueAsString(item);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot encode reflection", error);
    }
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private Item read(String json) {
    try {
      return mapper.readValue(json, Item.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot read reflection", error);
    }
  }

  /** 作業の振り返りにおいて、有効に該当するか判定する。 */
  static boolean valid(String value) {
    return value != null && !value.isBlank() && value.length() <= 512;
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  static String safe(String value, int length) {
    String safe =
        CredentialRedactor.redact(value == null ? "" : value).replaceAll("[\\p{Cntrl}]", " ");
    return safe.substring(0, Math.min(length, safe.length()));
  }
}
