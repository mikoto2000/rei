package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 目標を保存・検索するための窓口。
 *
 * <p>Durable goal identity, claims and pre-call reservations. Resume never replenishes a budget.
 */
@Repository
public class GoalRepository {
  /**
   * 入力を機能内で扱えるよう、判断基準一覧を解釈する。
   *
   * <p>Shared bounded criteria parser for Shell and named human APIs.
   */
  public static List<FileCriterion> parseCriteria(String json) {
    if (json == null || json.length() > 32768)
      throw new IllegalArgumentException("Bounded criteria JSON required");
    return JsonFileGoalCondition.parseCriteria(json);
  }

  /**
   * 目標の達成管理で受け渡すファイル判断基準を表す。
   *
   * @param relativeFile 相対ファイル
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param jsonPointer JSON変換参照位置
   * @param expectedJson 期待する内容JSON変換
   * @param predicateJson 判定条件JSON変換
   */
  public record FileCriterion(
      String relativeFile,
      String sha256,
      String jsonPointer,
      String expectedJson,
      String predicateJson) {
    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public FileCriterion(
        String relativeFile, String sha256, String jsonPointer, String expectedJson) {
      this(relativeFile, sha256, jsonPointer, expectedJson, null);
    }

    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public FileCriterion(String relativeFile, String sha256) {
      this(relativeFile, sha256, null, null);
    }

    /** 目標の達成管理において、JSON変換判断基準に該当するか判定する。 */
    public boolean jsonCriterion() {
      return jsonPointer != null || expectedJson != null || predicateJson != null;
    }
  }

  /**
   * 目標の達成管理で受け渡す目標を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param projectRoot 作業対象のルートディレクトリ
   * @param sessionId 会話の識別子
   * @param objective 目的
   * @param relativeFile 相対ファイル
   * @param sha256 内容の同一性を照合するSHA-256ハッシュ
   * @param maxRuns 最大実行一覧
   * @param maxLlmCalls 最大言語モデル呼び出し回数
   * @param attempts 試行回数
   * @param llmCallsUsed 言語モデル呼び出し回数使用量
   * @param status 状態
   * @param currentRunId 現在実行の識別子
   * @param reason 理由
   * @param criteria 判断基準一覧
   * @param maxTotalTokens 最大使用トークン数の合計
   * @param totalTokens 使用トークン数の合計
   * @param tokenUsageUnknown トークン使用状況不明
   * @param pendingLlmCalls 待機中言語モデル呼び出し回数
   * @param completion 完了条件
   * @param completionProof 完了条件確認の根拠
   */
  public record Goal(
      String id,
      String projectId,
      String projectRoot,
      String sessionId,
      String objective,
      String relativeFile,
      String sha256,
      int maxRuns,
      int maxLlmCalls,
      int attempts,
      int llmCallsUsed,
      String status,
      String currentRunId,
      String reason,
      List<FileCriterion> criteria,
      long maxTotalTokens,
      long totalTokens,
      boolean tokenUsageUnknown,
      int pendingLlmCalls,
      GoalCompletionGate.Definition completion,
      GoalCompletionGate.Proof completionProof) {
    /** 受け取った情報からGoalを作成する。 */
    public Goal {
      criteria = List.copyOf(criteria);
    }

    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Goal(
        String id,
        String projectId,
        String projectRoot,
        String sessionId,
        String objective,
        String relativeFile,
        String sha256,
        int maxRuns,
        int maxLlmCalls,
        int attempts,
        int llmCallsUsed,
        String status,
        String currentRunId,
        String reason,
        List<FileCriterion> criteria,
        long maxTotalTokens,
        long totalTokens,
        boolean tokenUsageUnknown,
        int pendingLlmCalls) {
      this(
          id,
          projectId,
          projectRoot,
          sessionId,
          objective,
          relativeFile,
          sha256,
          maxRuns,
          maxLlmCalls,
          attempts,
          llmCallsUsed,
          status,
          currentRunId,
          reason,
          criteria,
          maxTotalTokens,
          totalTokens,
          tokenUsageUnknown,
          pendingLlmCalls,
          null,
          null);
    }

    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Goal(
        String id,
        String projectId,
        String projectRoot,
        String sessionId,
        String objective,
        String relativeFile,
        String sha256,
        int maxRuns,
        int maxLlmCalls,
        int attempts,
        int llmCallsUsed,
        String status,
        String currentRunId,
        String reason,
        List<FileCriterion> criteria) {
      this(
          id,
          projectId,
          projectRoot,
          sessionId,
          objective,
          relativeFile,
          sha256,
          maxRuns,
          maxLlmCalls,
          attempts,
          llmCallsUsed,
          status,
          currentRunId,
          reason,
          criteria,
          0,
          0,
          false,
          0);
    }

    /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
    public Goal(
        String id,
        String projectId,
        String projectRoot,
        String sessionId,
        String objective,
        String relativeFile,
        String sha256,
        int maxRuns,
        int maxLlmCalls,
        int attempts,
        int llmCallsUsed,
        String status,
        String currentRunId,
        String reason) {
      this(
          id,
          projectId,
          projectRoot,
          sessionId,
          objective,
          relativeFile,
          sha256,
          maxRuns,
          maxLlmCalls,
          attempts,
          llmCallsUsed,
          status,
          currentRunId,
          reason,
          List.of(new FileCriterion(relativeFile, sha256)));
    }
  }

  /**
   * 目標の達成管理で受け渡す利用権の確保を表す。
   *
   * @param goal 目標
   * @param token トークン
   */
  public record Claim(Goal goal, String token) {}

  /**
   * 目標の達成管理で受け渡す判断基準キーを表す。
   *
   * @param file ファイル
   * @param predicate 判定条件
   */
  private record CriterionKey(Path file, String predicate) {}

  /**
   * 目標の達成管理で受け渡す試行を表す。
   *
   * @param runId 実行の識別子
   * @param number 数値
   * @param status 状態
   * @param reason 理由
   */
  public record Attempt(String runId, int number, String status, String reason) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新。 */
  private final TransactionTemplate transaction;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** この機能の動作設定。 */
  private final dev.mikoto2000.rei.llm.LlmProperties properties;

  /** 目標の達成管理で用いる一括反映を返す。 */
  <T> T atomic(java.util.function.Supplier<T> action) {
    return transaction.execute(status -> action.get());
  }

  /** 目標の達成管理で用いるデータベースへの接続を返す。 */
  JdbcClient jdbc() {
    return db;
  }

  /** 目標の達成管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public GoalRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    this(source, clock, new dev.mikoto2000.rei.llm.LlmProperties());
  }

  /** 指定された値や利用先を使って、GoalRepositoryを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public GoalRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source,
      Clock clock,
      dev.mikoto2000.rei.llm.LlmProperties properties) {
    this.properties = properties;
    db = JdbcClient.create(source);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.clock = clock;
    db.sql(GoalStageRepository.STAGES_SCHEMA).update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_stage_plans(goal TEXT PRIMARY KEY,version INTEGER NOT NULL,definition TEXT NOT NULL,changed_at TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goals(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,objective TEXT NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,max_runs INTEGER NOT NULL,max_calls INTEGER NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,used INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL,run TEXT,token TEXT,reason TEXT NOT NULL DEFAULT '')")
        .update();
    var columns =
        new HashSet<>(
            db.sql("PRAGMA table_info(agent_goals)").query((rs, n) -> rs.getString("name")).list());
    for (String column : List.of("max_tokens", "tokens_used", "tokens_unknown", "tokens_pending")) {
      if (!columns.contains(column))
        db.sql("ALTER TABLE agent_goals ADD COLUMN " + column + " INTEGER NOT NULL DEFAULT 0")
            .update();
    }
    for (String column : List.of("completion_json", "completion_proof_json", "completion_phase"))
      if (!columns.contains(column))
        db.sql("ALTER TABLE agent_goals ADD COLUMN " + column + " TEXT").update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_criteria(goal TEXT NOT NULL,ordinal INTEGER NOT NULL,file TEXT NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(goal,ordinal))")
        .update();
    var criterionColumns =
        new HashSet<>(
            db.sql("PRAGMA table_info(agent_goal_criteria)")
                .query((rs, n) -> rs.getString("name"))
                .list());
    for (String column : List.of("json_pointer", "expected_json", "predicate_json")) {
      if (!criterionColumns.contains(column))
        db.sql("ALTER TABLE agent_goal_criteria ADD COLUMN " + column + " TEXT").update();
    }
    db.sql(
            "CREATE UNIQUE INDEX IF NOT EXISTS agent_goals_running_session ON agent_goals(project,session) WHERE status='RUNNING'")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_attempts(goal TEXT NOT NULL,run TEXT PRIMARY KEY,number INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL DEFAULT '',UNIQUE(goal,number))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,goal TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,timestamp INTEGER NOT NULL)")
        .update();
  }

  /** 行。 */
  private final RowMapper<Goal> ROW =
      (rs, n) ->
          new Goal(
              rs.getString("id"),
              rs.getString("project"),
              rs.getString("root"),
              rs.getString("session"),
              rs.getString("objective"),
              rs.getString("file"),
              rs.getString("digest"),
              rs.getInt("max_runs"),
              rs.getInt("max_calls"),
              rs.getInt("attempts"),
              rs.getInt("used"),
              rs.getString("status"),
              rs.getString("run"),
              rs.getString("reason"),
              criteria(rs.getString("id"), rs.getString("file"), rs.getString("digest")),
              rs.getLong("max_tokens"),
              rs.getLong("tokens_used"),
              rs.getBoolean("tokens_unknown"),
              rs.getInt("tokens_pending"),
              completionDecode(
                  rs.getString("completion_json"), GoalCompletionGate.Definition.class),
              completionDecode(
                  rs.getString("completion_proof_json"), GoalCompletionGate.Proof.class));

  /** 完了条件JSON変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper COMPLETION_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(8192)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** 入力を機能内で扱えるよう、完了条件を解釈する。 */
  public static GoalCompletionGate.Definition parseCompletion(String json) {
    var value = completionDecode(json, GoalCompletionGate.Definition.class);
    GoalCompletionGate.validateDefinition(value);
    return value;
  }

  /** 入力を機能内で扱えるよう、完了条件確認の根拠を解釈する。 */
  public static GoalCompletionGate.Proof parseCompletionProof(String json) {
    var value = completionDecode(json, GoalCompletionGate.Proof.class);
    GoalCompletionGate.validateProof(value);
    return value;
  }

  /** 目標の達成管理で用いる完了条件受信形式の解釈を返す。 */
  private static <T> T completionDecode(String json, Class<T> type) {
    if (json == null) return null;
    try {
      if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
        throw new IllegalArgumentException("Completion JSON exceeds 32KiB");
      return COMPLETION_JSON.readValue(json, type);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Invalid bounded completion JSON");
    }
  }

  /** 目標の達成管理で用いる完了条件送信形式への変換を返す。 */
  private static String completionEncode(Object value) {
    try {
      String result = COMPLETION_JSON.writeValueAsString(value);
      if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768)
        throw new IllegalArgumentException("Completion JSON exceeds 32KiB");
      return result;
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("Completion JSON unavailable");
    }
  }

  /**
   * 目標の達成管理で用いる定義完了条件を返す。
   *
   * <p>Human definition changes are rejected while a Run can observe them or after terminal
   * completion.
   */
  public Goal defineCompletion(
      AgentRunContext owner, String id, GoalCompletionGate.Definition definition) {
    GoalCompletionGate.validateDefinition(definition);
    String json = completionEncode(definition);
    var goal = get(owner.projectId(), id);
    completionOwner(owner, goal);
    if (db.sql(
                "UPDATE agent_goals SET completion_json=?,completion_proof_json=NULL WHERE id=? AND project=? AND root=? AND session=? AND status NOT IN ('RUNNING','COMPLETED','CANCELLED') AND NOT EXISTS(SELECT 1 FROM agent_goal_stage_plans WHERE goal=agent_goals.id)")
            .params(
                json, id, owner.projectId(), owner.projectRoot().toString(), owner.conversationId())
            .update()
        != 1)
      throw new IllegalStateException(
          "Completion definition cannot change while running or terminal");
    return get(owner.projectId(), id);
  }

  /** 後から参照できるよう、完了条件確認の根拠を保存する。 */
  Goal saveCompletionProof(
      AgentRunContext owner, String id, GoalCompletionGate.Proof proof, boolean human) {
    GoalCompletionGate.validateProof(proof);
    var goal = get(owner.projectId(), id);
    completionOwner(owner, goal);
    if (db.sql(
                "UPDATE agent_goals SET completion_proof_json=? WHERE id=? AND project=? AND root=? AND session=? AND completion_json IS NOT NULL AND status NOT IN ('COMPLETED','CANCELLED') AND ((status='RUNNING' AND run=?) OR (status<>'RUNNING' AND ?))")
            .params(
                completionEncode(proof),
                id,
                owner.projectId(),
                owner.projectRoot().toString(),
                owner.conversationId(),
                owner.runId(),
                human)
            .update()
        != 1) throw new IllegalStateException("Goal evidence owner or state changed");
    return get(owner.projectId(), id);
  }

  /** 目標の達成管理に関する完了条件所有者を処理する。 */
  private static void completionOwner(AgentRunContext owner, Goal goal) {
    if (owner == null
        || owner.mode() != AgentRunContext.Mode.EXCLUSIVE
        || !owner.conversationId().equals(goal.sessionId())
        || !owner.projectRoot().toString().equals(goal.projectRoot()))
      throw new IllegalArgumentException("Owning exclusive Project/root/session required");
  }

  /** 指定された内容から新しい対象を作成する。 */
  public Goal create(
      AgentRunContext owner,
      String objective,
      String relativeFile,
      String sha256,
      int maxRuns,
      int maxLlmCalls) {
    if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
      throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
    return createBase(
        owner, objective, relativeFile, sha256.toLowerCase(Locale.ROOT), maxRuns, maxLlmCalls);
  }

  /** 新たな対象を利用できるよう、基準を作成する。 */
  private Goal createBase(
      AgentRunContext owner,
      String objective,
      String relativeFile,
      String sha256,
      int maxRuns,
      int maxLlmCalls) {
    if (owner == null
        || owner.projectId() == null
        || owner.projectId().isBlank()
        || owner.conversationId().isBlank())
      throw new IllegalArgumentException("Goal requires an owning Project and Session");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (objective == null || objective.isBlank() || objective.length() > 4096)
      throw new IllegalArgumentException("Objective must contain 1..4096 characters");
    validateFile(relativeFile);
    if (maxRuns < 1 || maxRuns > 10 || maxLlmCalls < 1 || maxLlmCalls > 100)
      throw new IllegalArgumentException("Goal budgets: 1..10 Runs and 1..100 LLM calls");
    String id = "goal-" + UUID.randomUUID();
    transaction.executeWithoutResult(
        status -> {
          if (db.sql(
                      "INSERT INTO agent_goals(id,project,root,session,objective,file,digest,max_runs,max_calls,status) SELECT ?,?,?,?,?,?,?,?,?,'READY' WHERE (SELECT COUNT(*) FROM agent_goals WHERE project=? AND status NOT IN ('COMPLETED','CANCELLED'))<256")
                  .params(
                      id,
                      owner.projectId(),
                      owner.projectRoot().toString(),
                      owner.conversationId(),
                      objective,
                      relativeFile,
                      sha256.toLowerCase(Locale.ROOT),
                      maxRuns,
                      maxLlmCalls,
                      owner.projectId())
                  .update()
              != 1) throw new IllegalStateException("Project goal limit reached (256)");
          history(id, "READY", "created");
          db.sql("UPDATE agent_goals SET max_tokens=? WHERE id=?")
              .params(properties.getOutputLimit().getMaxTotalTokensPerGoal(), id)
              .update();
        });
    return get(owner.projectId(), id);
  }

  /** 目標の達成管理で用いる判断基準一覧を返す。 */
  private List<FileCriterion> criteria(String id, String file, String digest) {
    var items =
        db.sql(
                "SELECT file,digest,json_pointer,expected_json,predicate_json FROM agent_goal_criteria WHERE goal=? ORDER BY ordinal")
            .param(id)
            .query(
                (rs, n) ->
                    new FileCriterion(
                        rs.getString("file"),
                        rs.getString("digest"),
                        rs.getString("json_pointer"),
                        rs.getString("expected_json"),
                        rs.getString("predicate_json")))
            .list();
    return items.isEmpty() ? List.of(new FileCriterion(file, digest)) : items;
  }

  /** 指定された内容から新しい対象を作成する。 */
  public Goal create(
      AgentRunContext owner,
      String objective,
      List<FileCriterion> criteria,
      int maxRuns,
      int maxLlmCalls) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (criteria == null || criteria.isEmpty() || criteria.size() > 16)
      throw new IllegalArgumentException("Goal requires 1..16 file criteria");
    var normalized = new ArrayList<FileCriterion>();
    var paths = new HashSet<CriterionKey>();
    for (var item : criteria) {
      if (item == null) throw new IllegalArgumentException("File criterion is required");
      validateFile(item.relativeFile());
      var path = Path.of(item.relativeFile()).normalize();
      String relative = path.toString().replace('\\', '/');
      if (!paths.add(
          new CriterionKey(
              path,
              item.predicateJson() != null
                  ? "predicate"
                  : item.jsonCriterion() ? "json:" + item.jsonPointer() : "digest")))
        throw new IllegalArgumentException("Duplicate completion criterion");
      if (item.jsonCriterion()) {
        if (item.sha256() != null && !item.sha256().isEmpty())
          throw new IllegalArgumentException("Use SHA-256 or JSON scalar criteria");
        if (item.predicateJson() != null) {
          if (item.jsonPointer() != null || item.expectedJson() != null)
            throw new IllegalArgumentException("Use scalar or declarative JSON predicate");
          var predicate =
              dev.mikoto2000.rei.core.predicate.DeclarativePredicate.parse(item.predicateJson());
          normalized.add(new FileCriterion(relative, "", null, null, predicate.json()));
        } else {
          var condition = JsonFileGoalCondition.parse(item.jsonPointer(), item.expectedJson());
          normalized.add(
              new FileCriterion(relative, "", condition.pointer(), condition.expectedJson()));
        }
      } else {
        if (item.sha256() == null || !item.sha256().matches("[a-fA-F0-9]{64}"))
          throw new IllegalArgumentException(
              "Expected SHA-256 must contain 64 hexadecimal characters");
        normalized.add(new FileCriterion(relative, item.sha256().toLowerCase(Locale.ROOT)));
      }
    }
    return transaction.execute(
        status -> {
          var first = normalized.getFirst();
          var goal =
              createBase(
                  owner, objective, first.relativeFile(), first.sha256(), maxRuns, maxLlmCalls);
          for (int i = 0; i < normalized.size(); i++) {
            var item = normalized.get(i);
            db.sql(
                    "INSERT INTO agent_goal_criteria(goal,ordinal,file,digest,json_pointer,expected_json,predicate_json) VALUES(?,?,?,?,?,?,?)")
                .params(
                    goal.id(),
                    i,
                    item.relativeFile(),
                    item.sha256(),
                    item.jsonPointer(),
                    item.expectedJson(),
                    item.predicateJson())
                .update();
          }
          return get(owner.projectId(), goal.id());
        });
  }

  /** 指定された内容から新しい対象を作成する。 */
  public Goal create(
      AgentRunContext owner,
      String objective,
      List<FileCriterion> criteria,
      GoalCompletionGate.Definition definition,
      int maxRuns,
      int maxLlmCalls) {
    GoalCompletionGate.validateDefinition(definition);
    return transaction.execute(
        status -> {
          var goal = create(owner, objective, criteria, maxRuns, maxLlmCalls);
          return defineCompletion(owner, goal.id(), definition);
        });
  }

  /** 利用条件に合わない指定を受け付けないよう、ファイルを検証する。 */
  static void validateFile(String file) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (file == null || file.isBlank() || file.length() > 1024)
      throw new IllegalArgumentException("Relative file is required (up to 1024 characters)");
    var path = Path.of(file);
    if (path.isAbsolute()
        || path.getRoot() != null
        || file.contains(":")
        || path.normalize().toString().isEmpty())
      throw new IllegalArgumentException("File must be a Project-relative path");
    for (var part : path)
      if (part.toString().equals(".."))
        throw new IllegalArgumentException("Parent traversal is not permitted");
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Goal get(String project, String id) {
    return transaction.execute(
        status ->
            db.sql("SELECT * FROM agent_goals WHERE project=? AND id=?")
                .params(project, id)
                .query(ROW)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Goal not found in this Project")));
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Goal> list(String project) {
    return transaction.execute(
        status ->
            db.sql("SELECT * FROM agent_goals WHERE project=? ORDER BY id LIMIT 256")
                .param(project)
                .query(ROW)
                .list());
  }

  /** 目標の達成管理で用いるタスクページを返す。 */
  public List<Goal> taskPage(String project, String root, String session, String after, int limit) {
    if (limit < 1 || limit > 101)
      throw new IllegalArgumentException("Invalid projection page limit");
    return db.sql(
            "SELECT * FROM agent_goals WHERE project=:project AND root=:root AND (:session IS NULL OR session=:session) AND id>:after ORDER BY id LIMIT :limit")
        .param("project", project)
        .param("root", root)
        .param("session", session)
        .param("after", after)
        .param("limit", limit)
        .query(ROW)
        .list();
  }

  /** 目標の達成管理で用いるタスク起点を返す。 */
  public Optional<Goal> taskOrigin(String project, String root, String session, String run) {
    return db.sql(
            "SELECT * FROM agent_goals WHERE project=:project AND root=:root AND session=:session AND (run=:run OR id IN (SELECT goal FROM agent_goal_attempts WHERE run=:run)) ORDER BY id LIMIT 1")
        .param("project", project)
        .param("root", root)
        .param("session", session)
        .param("run", run)
        .query(ROW)
        .optional();
  }

  /** 対象を処理する担当を決めるため、目標保存と検索の窓口の利用権を確保する。 */
  public Claim claim(String project, String id) {
    return transaction.execute(
        status -> {
          get(project, id);
          String token = UUID.randomUUID().toString();
          var goal =
              db.sql(
                      "UPDATE agent_goals SET status='RUNNING',token=?,reason='' WHERE project=? AND id=? AND status IN ('READY','PAUSED','WAITING_APPROVAL','FAILED','BLOCKED') AND attempts<max_runs AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_pending=0 AND tokens_used<max_tokens)) RETURNING *")
                  .params(token, project, id)
                  .query(ROW)
                  .optional()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Goal is running, terminal, or its budget is exhausted"));
          history(id, "RUNNING", "explicit_run");
          return new Claim(goal, token);
        });
  }

  /** 対象を処理する担当を決めるため、目標保存と検索の窓口の利用権を確保する。 */
  Claim claim(Goal expected, GoalWaitRepository.Wait wait) {
    return transaction.execute(
        status -> {
          db.sql("UPDATE agent_goals SET attempts=attempts WHERE project=? AND id=?")
              .params(expected.projectId(), expected.id())
              .update();
          if (db.sql(
                      "SELECT COUNT(*) FROM agent_goal_waits WHERE project=? AND id=? AND goal=? AND state='RESUMING' AND version=?")
                  .params(expected.projectId(), wait.id(), expected.id(), wait.version())
                  .query(Integer.class)
                  .single()
              != 1) throw new IllegalStateException("Wait admission changed");
          if (!get(expected.projectId(), expected.id()).equals(expected))
            throw new IllegalStateException("Goal changed before resume admission");
          return claim(expected.projectId(), expected.id());
        });
  }

  /** 目標の達成管理で用いる開始試行を返す。 */
  public String beginAttempt(Claim claim) {
    return transaction.execute(
        status -> {
          String run = UUID.randomUUID().toString();
          int count =
              db.sql(
                      "UPDATE agent_goals SET attempts=attempts+1,run=? WHERE id=? AND project=? AND token=? AND status='RUNNING' AND attempts<max_runs AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_pending=0 AND tokens_used<max_tokens))")
                  .params(run, claim.goal().id(), claim.goal().projectId(), claim.token())
                  .update();
          if (count != 1)
            throw new IllegalStateException("Goal claim inactive or budget exhausted");
          var goal = get(claim.goal().projectId(), claim.goal().id());
          db.sql("INSERT INTO agent_goal_attempts(goal,run,number,status) VALUES(?,?,?,'RUNNING')")
              .params(goal.id(), run, goal.attempts())
              .update();
          return run;
        });
  }

  /**
   * 目標の達成管理において、予約言語モデルに該当するか判定する。
   *
   * <p>Charged before an LLM invocation; failed or uncertain calls never restore the reservation.
   */
  public boolean reserveLlm(Claim claim) {
    return db.sql(
                "UPDATE agent_goals SET used=used+1,tokens_pending=tokens_pending+CASE WHEN max_tokens>0 THEN 1 ELSE 0 END WHERE id=? AND project=? AND token=? AND status='RUNNING' AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_used<max_tokens))")
            .params(claim.goal().id(), claim.goal().projectId(), claim.token())
            .update()
        == 1;
  }

  /** 目標の達成管理で用いる残量言語モデルを返す。 */
  public int remainingLlm(Claim claim) {
    return db.sql(
            "SELECT max_calls-used FROM agent_goals WHERE id=? AND project=? AND token=? AND status='RUNNING' AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_used<max_tokens))")
        .params(claim.goal().id(), claim.goal().projectId(), claim.token())
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  /**
   * 使用したトークン数を実行予算へ記録する。
   *
   * <p>One report for each reserved invocation; unknown usage is a durable, irreversible stop.
   */
  public void recordTotalTokens(Claim claim, String run, Integer tokens) {
    if (claim.goal().maxTotalTokens() == 0) return;
    boolean known = tokens != null && tokens > 0;
    int amount = known ? tokens : 0;
    var goal =
        transaction.execute(
            status -> {
              int updated =
                  db.sql(
                          "UPDATE agent_goals SET tokens_used=CASE WHEN tokens_used>9223372036854775807-? THEN 9223372036854775807 ELSE tokens_used+? END,tokens_unknown=CASE WHEN ? THEN tokens_unknown ELSE 1 END,tokens_pending=tokens_pending-1 WHERE id=? AND project=? AND token=? AND run=? AND status='RUNNING' AND tokens_pending>0")
                      .params(
                          amount,
                          amount,
                          known,
                          claim.goal().id(),
                          claim.goal().projectId(),
                          claim.token(),
                          run)
                      .update();
              if (updated != 1)
                throw new IllegalStateException(
                    "Goal usage report has no owned pending invocation");
              return get(claim.goal().projectId(), claim.goal().id());
            });
    if (goal.tokenUsageUnknown())
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN);
    if (goal.totalTokens() > goal.maxTotalTokens())
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
              .TOKEN_BUDGET_EXCEEDED);
  }

  /** 目標の達成管理において、トークン残量なしに該当するか判定する。 */
  public boolean tokenExhausted(Claim claim) {
    var goal = get(claim.goal().projectId(), claim.goal().id());
    return goal.maxTotalTokens() > 0
        && (goal.tokenUsageUnknown() || goal.totalTokens() >= goal.maxTotalTokens());
  }

  /** 目標の達成管理で用いるモデル予算を返す。 */
  public dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation modelBudget(
      Claim claim, String run) {
    return new dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation() {
      /** 目標の達成管理で判断に用いる試行予約の条件を確認する。 */
      public boolean tryReserve() {
        return reserveLlm(claim, run);
      }

      /** 目標の達成管理で用いる残量を返す。 */
      public int remaining() {
        return active(claim, run) ? remainingLlm(claim) : 0;
      }

      /** トークン数による実行予算の制限が有効かどうかを返す。 */
      public boolean tokenLimitEnabled() {
        return claim.goal().maxTotalTokens() > 0;
      }

      /** 目標の達成管理において、トークン残量なしに該当するか判定する。 */
      public boolean tokenExhausted() {
        return tokenLimitEnabled() && GoalRepository.this.tokenExhausted(claim);
      }

      /** 目標の達成管理において、使用状況不明に該当するか判定する。 */
      public boolean usageUnknown() {
        return tokenLimitEnabled()
            && get(claim.goal().projectId(), claim.goal().id()).tokenUsageUnknown();
      }

      /** 使用したトークン数を実行予算へ記録する。 */
      public void recordTotalTokens(Integer tokens) {
        GoalRepository.this.recordTotalTokens(claim, run, tokens);
      }
    };
  }

  /** 目標の達成管理において、予約言語モデルに該当するか判定する。 */
  public boolean reserveLlm(Claim claim, String run) {
    return db.sql(
                "UPDATE agent_goals SET used=used+1,tokens_pending=tokens_pending+CASE WHEN max_tokens>0 THEN 1 ELSE 0 END WHERE id=? AND project=? AND token=? AND run=? AND status='RUNNING' AND used<max_calls AND (max_tokens=0 OR (tokens_unknown=0 AND tokens_used<max_tokens)) AND EXISTS(SELECT 1 FROM agent_goal_attempts WHERE goal=agent_goals.id AND run=? AND status='RUNNING')")
            .params(claim.goal().id(), claim.goal().projectId(), claim.token(), run, run)
            .update()
        == 1;
  }

  /** 目標の達成管理において、活動中に該当するか判定する。 */
  public boolean active(Claim claim, String run) {
    return db.sql(
                "SELECT COUNT(*) FROM agent_goals WHERE id=? AND project=? AND token=? AND run=? AND status='RUNNING' AND EXISTS(SELECT 1 FROM agent_goal_attempts WHERE goal=agent_goals.id AND run=? AND status='RUNNING')")
            .params(claim.goal().id(), claim.goal().projectId(), claim.token(), run, run)
            .query(Integer.class)
            .single()
        == 1;
  }

  /** 目標の達成管理において、活動中に該当するか判定する。 */
  public boolean active(Claim claim) {
    return db.sql(
                "SELECT COUNT(*) FROM agent_goals WHERE id=? AND project=? AND token=? AND status='RUNNING'")
            .params(claim.goal().id(), claim.goal().projectId(), claim.token())
            .query(Integer.class)
            .single()
        == 1;
  }

  /** 後から経緯や使用量を確認できるよう、試行を記録する。 */
  public void recordAttempt(Claim claim, String run, String state, String reason) {
    if (!Set.of("VERIFIED", "UNVERIFIED", "FAILED", "CANCELLED", "WAITING_APPROVAL", "BLOCKED")
        .contains(state)) throw new IllegalArgumentException("Invalid attempt state");
    transaction.executeWithoutResult(
        status -> {
          int updated =
              db.sql(
                      "UPDATE agent_goal_attempts SET status=?,reason=? WHERE goal=? AND run=? AND status='RUNNING' AND EXISTS(SELECT 1 FROM agent_goals WHERE id=? AND project=? AND token=? AND run=?)")
                  .params(
                      state,
                      reason,
                      claim.goal().id(),
                      run,
                      claim.goal().id(),
                      claim.goal().projectId(),
                      claim.token(),
                      run)
                  .update();
          if (updated != 1) throw new IllegalStateException("Attempt is no longer owned");
          history(claim.goal().id(), state, reason);
        });
  }

  /** 実行中の処理を停止する。 */
  public Goal stop(Claim claim, String state, String reason) {
    if (!Set.of("COMPLETED", "FAILED", "PAUSED", "BLOCKED", "WAITING_APPROVAL").contains(state))
      throw new IllegalArgumentException("Invalid Goal state");
    transaction.executeWithoutResult(
        status -> {
          String effectiveState = state;
          String effectiveReason = reason;
          if (state.equals("COMPLETED")) {
            if (db.sql(
                        "UPDATE agent_goals SET attempts=attempts WHERE id=? AND project=? AND token=? AND status='RUNNING'")
                    .params(claim.goal().id(), claim.goal().projectId(), claim.token())
                    .update()
                != 1) throw new IllegalStateException("Goal claim inactive");
            if (db.sql(
                        "SELECT COUNT(*) FROM agent_goals WHERE id=? AND EXISTS(SELECT 1 FROM agent_goal_stages s WHERE s.goal=agent_goals.id AND (s.state<>'VERIFIED' OR s.evidence IS NULL OR NOT EXISTS(SELECT 1 FROM agent_goal_stage_plans p WHERE p.goal=s.goal AND p.version=s.plan_version)))")
                    .param(claim.goal().id())
                    .query(Integer.class)
                    .single()
                > 0) {
              effectiveState = "BLOCKED";
              effectiveReason = "stage_verification_required";
            }
          }
          if (db.sql(
                      "UPDATE agent_goals SET status=?,reason=?,token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE id=? AND project=? AND token=? AND status='RUNNING'")
                  .params(
                      effectiveState,
                      effectiveReason,
                      claim.goal().id(),
                      claim.goal().projectId(),
                      claim.token())
                  .update()
              != 1) throw new IllegalStateException("Goal claim inactive");
          history(claim.goal().id(), effectiveState, effectiveReason);
        });
    return get(claim.goal().projectId(), claim.goal().id());
  }

  /** 目標の達成管理で用いる確認済みなし実行を返す。 */
  public Goal verifiedWithoutRun(String project, String id) {
    var goal = get(project, id);
    return verifiedWithoutRun(
        project,
        id,
        goal.criteria().stream().anyMatch(FileCriterion::jsonCriterion)
            ? "criteria_verified"
            : "file_digest_verified");
  }

  /** 目標の達成管理に関する古い状態段階完了条件を処理する。 */
  void staleStageCompletion(Goal expected) {
    if (db.sql(
                "UPDATE agent_goals SET status='BLOCKED',reason='stage_evidence_stale' WHERE project=? AND id=? AND status='COMPLETED' AND EXISTS(SELECT 1 FROM agent_goal_stages WHERE goal=agent_goals.id AND state<>'VERIFIED')")
            .params(expected.projectId(), expected.id())
            .update()
        == 1) history(expected.id(), "BLOCKED", "stage_evidence_stale");
  }

  /** 目標の達成管理で用いる確認済みなし実行を返す。 */
  public Goal verifiedWithoutRun(String project, String id, String verifiedReason) {
    return verifiedWithoutRun(get(project, id), verifiedReason);
  }

  /** 目標の達成管理で用いる確認済みなし実行を返す。 */
  public Goal verifiedWithoutRun(Goal expected, String verifiedReason) {
    String project = expected.projectId(), id = expected.id();
    String definition =
        expected.completion() == null ? null : completionEncode(expected.completion());
    String proof =
        expected.completionProof() == null ? null : completionEncode(expected.completionProof());
    transaction.executeWithoutResult(
        status -> {
          get(project, id);
          String reason = verifiedReason;
          if (db.sql(
                      "UPDATE agent_goals SET status='COMPLETED',reason=? WHERE project=? AND id=? AND status NOT IN ('RUNNING','CANCELLED','COMPLETED') AND completion_json IS ? AND completion_proof_json IS ? AND NOT EXISTS(SELECT 1 FROM agent_goal_stages s WHERE s.goal=agent_goals.id AND (s.state<>'VERIFIED' OR s.evidence IS NULL OR NOT EXISTS(SELECT 1 FROM agent_goal_stage_plans p WHERE p.goal=s.goal AND p.version=s.plan_version)))")
                  .params(reason, project, id, definition, proof)
                  .update()
              != 1)
            throw new IllegalStateException(
                "Goal is running, terminal, or completion definition/evidence changed");
          history(id, "COMPLETED", reason);
        });
    return get(project, id);
  }

  /**
   * 現在の状態と記録を照合し、差異に応じて管理状態を整える。
   *
   * <p>Human reconciliation only: unknown side effects remain unknown; no budget or evidence is
   * restored.
   */
  public Goal reconcile(String project, String id, String expectedRunId) {
    transaction.executeWithoutResult(
        status -> {
          get(project, id);
          if (db.sql(
                      "UPDATE agent_goals SET status='PAUSED',reason='uncertain_run_reconciled',token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE project=? AND id=? AND status='RUNNING' AND run IS ?")
                  .params(project, id, expectedRunId)
                  .update()
              != 1)
            throw new IllegalStateException("Goal is no longer running with the specified Run ID");
          db.sql(
                  "UPDATE agent_goal_attempts SET status='BLOCKED',reason='uncertain_run_reconciled' WHERE goal=? AND status='RUNNING'")
              .param(id)
              .update();
          history(id, "PAUSED", "uncertain_run_reconciled");
        });
    return get(project, id);
  }

  /** 対象の実行をキャンセルする。 */
  public Goal cancel(String project, String id) {
    return cancel(project, id, null, null);
  }

  /** 対象の実行をキャンセルする。 */
  public Goal cancel(String project, String id, String expectedRun, long expectedAttempts) {
    return cancel(project, id, expectedRun, Long.valueOf(expectedAttempts));
  }

  /** 対象の実行をキャンセルする。 */
  private Goal cancel(String project, String id, String expectedRun, Long expectedAttempts) {
    transaction.executeWithoutResult(
        status -> {
          get(project, id);
          if (db.sql(
                      "UPDATE agent_goals SET status='CANCELLED',reason='human_cancelled',token=NULL,tokens_unknown=CASE WHEN max_tokens>0 AND tokens_pending>0 THEN 1 ELSE tokens_unknown END WHERE project=:project AND id=:id AND status NOT IN ('COMPLETED','CANCELLED') AND (:attempts IS NULL OR (attempts=:attempts AND run IS :run))")
                  .param("project", project)
                  .param("id", id)
                  .param("attempts", expectedAttempts)
                  .param("run", expectedRun)
                  .update()
              != 1) {
            if (expectedAttempts != null)
              throw new dev.mikoto2000.rei.application.state.OperationConflictException();
            throw new IllegalStateException("Goal is already terminal");
          }
          db.sql(
                  "UPDATE agent_goal_attempts SET status='CANCELLED',reason='human_cancelled' WHERE goal=? AND status='RUNNING'")
              .param(id)
              .update();
          history(id, "CANCELLED", "human_cancelled");
        });
    return get(project, id);
  }

  /** 目標の達成管理で用いる試行回数を返す。 */
  public List<Attempt> attempts(String project, String id) {
    get(project, id);
    return db.sql("SELECT * FROM agent_goal_attempts WHERE goal=? ORDER BY number")
        .param(id)
        .query(
            (rs, n) ->
                new Attempt(
                    rs.getString("run"),
                    rs.getInt("number"),
                    rs.getString("status"),
                    rs.getString("reason")))
        .list();
  }

  /**
   * 目標の達成管理で受け渡す履歴を表す。
   *
   * @param status 状態
   * @param reason 理由
   * @param timestamp 記録日時
   */
  public record History(String status, String reason, Instant timestamp) {}

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  public List<History> history(String project, String id) {
    get(project, id);
    return db.sql("SELECT * FROM agent_goal_history WHERE goal=? ORDER BY sequence")
        .param(id)
        .query(
            (rs, n) ->
                new History(
                    rs.getString("status"),
                    rs.getString("reason"),
                    Instant.ofEpochMilli(rs.getLong("timestamp"))))
        .list();
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  private void history(String id, String status, String reason) {
    db.sql("INSERT INTO agent_goal_history(goal,status,reason,timestamp) VALUES(?,?,?,?)")
        .params(id, status, reason, clock.millis())
        .update();
  }

  /**
   * 目標の達成管理で用いる完了条件段階を返す。
   *
   * <p>Supplemental lifecycle preserving existing claims, reservations and old rows.
   */
  public String completionPhase(String project, String id) {
    var goal = get(project, id);
    if (goal.status().equals("RUNNING")) {
      String phase =
          db.sql("SELECT completion_phase FROM agent_goals WHERE project=? AND id=?")
              .params(project, id)
              .query(String.class)
              .single();
      return phase != null && Set.of("VERIFYING", "REPAIRING").contains(phase) ? phase : "RUNNING";
    }
    return switch (goal.status()) {
      case "WAITING_APPROVAL", "PAUSED" -> "WAITING";
      default -> goal.status();
    };
  }

  /** 目標の達成管理において、完了条件段階に該当するか判定する。 */
  boolean completionPhase(Claim claim, String phase) {
    if (!Set.of("RUNNING", "VERIFYING", "REPAIRING").contains(phase))
      throw new IllegalArgumentException("Invalid active completion phase");
    return db.sql(
                "UPDATE agent_goals SET completion_phase=? WHERE id=? AND project=? AND token=? AND status='RUNNING'")
            .params(phase, claim.goal().id(), claim.goal().projectId(), claim.token())
            .update()
        == 1;
  }

  /**
   * Capture only immutable execution authority, using unambiguous JSON rather than display text.
   */
  public String secretaryDefinitionFingerprint(Goal goal, long planVersion) {
    try {
      var definition =
          COMPLETION_JSON.writeValueAsBytes(
              java.util.Arrays.asList(
                  goal.id(),
                  goal.projectId(),
                  goal.projectRoot(),
                  goal.sessionId(),
                  goal.objective(),
                  goal.criteria(),
                  goal.maxRuns(),
                  goal.maxLlmCalls(),
                  goal.maxTotalTokens(),
                  goal.completion(),
                  planVersion));
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(definition));
    } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("Goal definition unavailable", error);
    }
  }

  /** Connect the durable owner to the already shared model budget. */
  public dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation secretaryBudget(
      java.util.function.BooleanSupplier reserve, java.util.function.IntSupplier remaining) {
    return dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.of(reserve, remaining);
  }

  /** Keep the existing Goal reservation and token accounting under revocable admission. */
  public dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation guardedBudget(
      dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation parent,
      java.util.function.BooleanSupplier allowed) {
    return dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation.guarded(parent, allowed);
  }
}
