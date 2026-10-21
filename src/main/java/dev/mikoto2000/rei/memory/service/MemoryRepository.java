package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.memory.model.LongTermMemory;
import dev.mikoto2000.rei.memory.model.MemoryCandidate;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemorySource;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 記憶を保存・検索するための窓口。
 *
 * <p>Additive migration and transactional persistence on the existing memory DataSource.
 */
@Repository
public class MemoryRepository {
  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate transactions;

  /** 指定された値や利用先を使って、MemoryRepositoryを初期化する。 */
  public MemoryRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, MemoryService legacy) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction(
        () -> {
          column("memories", "project_id", "TEXT");
          column("memories", "summary", "TEXT");
          column("memories", "importance", "REAL NOT NULL DEFAULT 0.5");
          column("memories", "last_accessed_at", "TEXT");
          column("memories", "valid_from", "TEXT");
          column("memories", "superseded_by", "TEXT");
          column("memories", "content_key", "TEXT");
          column("memory_sources", "session_id", "TEXT");
          column("memory_sources", "turn_id", "TEXT");
          db.sql(
                  "CREATE INDEX IF NOT EXISTS memory_scope_status ON memories(scope, project_id, status)")
              .update();
          for (var row :
              db.sql(
                      "SELECT id,content FROM memories WHERE summary IS NOT NULL AND content_key IS NULL")
                  .query((r, n) -> Map.entry(r.getString("id"), r.getString("content")))
                  .list())
            db.sql("UPDATE memories SET content_key=? WHERE id=?")
                .params(MemoryResolver.normalize(row.getValue()), row.getKey())
                .update();
          db.sql(
                  "CREATE UNIQUE INDEX IF NOT EXISTS active_memory_content ON memories(scope,coalesce(project_id,''),content_key) WHERE status='ACTIVE' AND content_key IS NOT NULL")
              .update();
          db.sql(
                  "CREATE VIRTUAL TABLE IF NOT EXISTS long_term_memory_fts USING fts5(memory_id UNINDEXED, content, summary, tags, tokenize='trigram')")
              .update();
          db.sql(
                  """
          CREATE TABLE IF NOT EXISTS sleep_runs (
            id TEXT PRIMARY KEY, session_id TEXT NOT NULL, project_id TEXT NOT NULL,
            started_at TEXT NOT NULL, completed_at TEXT NOT NULL, status TEXT NOT NULL,
            from_sequence INTEGER NOT NULL, to_sequence INTEGER NOT NULL, processed_turns INTEGER NOT NULL,
            candidate_count INTEGER NOT NULL, added INTEGER NOT NULL, updated INTEGER NOT NULL,
            merged INTEGER NOT NULL, superseded INTEGER NOT NULL, ignored INTEGER NOT NULL,
            conflicts INTEGER NOT NULL, failed INTEGER NOT NULL)
          """)
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS sleep_model_usage (project_id TEXT PRIMARY KEY, calls INTEGER NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 0, unknown INTEGER NOT NULL DEFAULT 0)")
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS auto_sleep_state(session_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,failures INTEGER NOT NULL DEFAULT 0,stage INTEGER NOT NULL DEFAULT 0,stopped_at TEXT,last_failure_at TEXT,code TEXT,description TEXT,revision INTEGER NOT NULL DEFAULT 0)")
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS auto_sleep_requests(session_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,revision INTEGER NOT NULL,cause TEXT NOT NULL)")
              .update();
          db.sql(
                  "CREATE TABLE IF NOT EXISTS verified_reflection_memories(project_id TEXT NOT NULL,reflection_id TEXT NOT NULL,goal_id TEXT NOT NULL,session_id TEXT NOT NULL,memory_id TEXT NOT NULL UNIQUE,criteria_sha256 TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(project_id,reflection_id))")
              .update();
          return null;
        });
  }

  /** 長期記憶の保存と検索に関する列を処理する。 */
  private void column(String table, String name, String definition) {
    if (!db.sql("PRAGMA table_info(" + table + ")")
        .query((r, n) -> r.getString("name"))
        .list()
        .contains(name))
      db.sql("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition).update();
  }

  /** 長期記憶の保存と検索で用いる一連の更新を返す。 */
  public <T> T transaction(Supplier<T> action) {
    return transactions.execute(status -> action.get());
  }

  /**
   * 長期記憶の保存と検索で受け渡す自動状態を表す。
   *
   * @param session 会話
   * @param project プロジェクト
   * @param failures 失敗一覧
   * @param stage 段階
   * @param stoppedAt 停止済み日時
   * @param lastFailureAt 直近失敗情報日時
   * @param code コード
   * @param description 説明
   * @param revision 改訂番号
   */
  public record AutoState(
      String session,
      String project,
      int failures,
      int stage,
      String stoppedAt,
      String lastFailureAt,
      String code,
      String description,
      long revision) {
    /** 受け取った情報からAutoStateを作成する。 */
    public AutoState {
      if (failures < 0 || stage < 0 || stage > 30 || revision < 0)
        throw new IllegalStateException("Invalid Auto Sleep state");
    }
  }

  /** 長期記憶の保存と検索で用いる自動状態を返す。 */
  public AutoState autoState(String session, String project) {
    if (session == null
        || session.isBlank()
        || session.length() > 512
        || project == null
        || project.isBlank()
        || project.length() > 128) throw new IllegalArgumentException("Select Session and Project");
    String encoded = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(session);
    if (encoded != null && !encoded.equals(project))
      throw new IllegalArgumentException("Session belongs to another Project");
    return transaction(
        () -> {
          db.sql("INSERT OR IGNORE INTO auto_sleep_state(session_id,project_id) VALUES(?,?)")
              .params(session, project)
              .update();
          var state =
              db.sql("SELECT * FROM auto_sleep_state WHERE session_id=?")
                  .param(session)
                  .query(
                      (r, n) ->
                          new AutoState(
                              r.getString("session_id"),
                              r.getString("project_id"),
                              r.getInt("failures"),
                              r.getInt("stage"),
                              r.getString("stopped_at"),
                              r.getString("last_failure_at"),
                              r.getString("code"),
                              r.getString("description"),
                              r.getLong("revision")))
                  .single();
          if (!state.project().equals(project))
            throw new IllegalArgumentException("Session belongs to another Project");
          return state;
        });
  }

  /** 最新の内容を後続の処理に反映するため、自動状態を更新する。 */
  public AutoState updateAutoState(
      AutoState expected, String code, String description, String timestamp, int limit) {
    return transaction(
        () -> {
          autoState(expected.session(), expected.project());
          boolean reset = code == null;
          int failures = reset ? 0 : Math.addExact(expected.failures(), 1);
          int stage =
              reset ? 0 : Math.min(30, expected.stage() + ("MODEL_TIMEOUT".equals(code) ? 1 : 0));
          int changed =
              db.sql(
                      "UPDATE auto_sleep_state SET failures=?,stage=?,stopped_at=?,last_failure_at=?,code=?,description=?,revision=revision+1 WHERE session_id=? AND project_id=? AND revision=? AND revision<9223372036854775807")
                  .params(
                      failures,
                      stage,
                      reset ? null : failures >= limit ? timestamp : expected.stoppedAt(),
                      reset ? null : timestamp,
                      code,
                      description,
                      expected.session(),
                      expected.project(),
                      expected.revision())
                  .update();
          if (changed != 1) throw new IllegalStateException("Stale Auto Sleep revision");
          return autoState(expected.session(), expected.project());
        });
  }

  /**
   * 長期記憶の保存と検索で受け渡す自動待機要求を表す。
   *
   * @param sessionId 会話の識別子
   * @param projectId 所属プロジェクトの識別子
   * @param revision 改訂番号
   * @param cause 原因
   */
  public record AutoSleepRequest(String sessionId, String projectId, long revision, String cause) {}

  /**
   * 長期記憶の保存と検索に関する要求自動待機を処理する。
   *
   * <p>Ending records intent only; a later idle worker performs consolidation.
   */
  public void requestAutoSleep(String session, String project, String cause) {
    if (session == null
        || session.isBlank()
        || session.length() > 512
        || project == null
        || project.isBlank()
        || project.length() > 128
        || cause == null
        || !Set.of("session_end", "shutdown").contains(cause))
      throw new IllegalArgumentException("Invalid Auto Sleep request");
    String encoded = dev.mikoto2000.rei.core.project.ProjectStorage.projectId(session);
    if (encoded != null && !encoded.equals(project))
      throw new IllegalArgumentException("Session belongs to another Project");
    int changed =
        db.sql(
                """
        INSERT INTO auto_sleep_requests(session_id,project_id,revision,cause)
        SELECT ?,?,1,? WHERE (SELECT COUNT(*) FROM auto_sleep_requests)<256
          OR EXISTS(SELECT 1 FROM auto_sleep_requests WHERE session_id=?)
        ON CONFLICT(session_id) DO UPDATE SET revision=revision+1,cause=excluded.cause
          WHERE auto_sleep_requests.project_id=excluded.project_id AND revision<9223372036854775807
        """)
            .params(session, project, cause, session)
            .update();
    if (changed != 1)
      throw new IllegalStateException("Auto Sleep request limit or ownership conflict");
  }

  /** 長期記憶の保存と検索で用いる待機中自動待機要求一覧を返す。 */
  public List<AutoSleepRequest> pendingAutoSleepRequests() {
    return db.sql(
            "SELECT session_id,project_id,revision,cause FROM auto_sleep_requests ORDER BY revision,session_id LIMIT 256")
        .query(
            (r, n) ->
                new AutoSleepRequest(r.getString(1), r.getString(2), r.getLong(3), r.getString(4)))
        .list();
  }

  /** 長期記憶の保存と検索において、完了自動待機要求に該当するか判定する。 */
  public boolean completeAutoSleepRequest(AutoSleepRequest request) {
    return db.sql(
                "DELETE FROM auto_sleep_requests WHERE session_id=? AND project_id=? AND revision=?")
            .params(request.sessionId(), request.projectId(), request.revision())
            .update()
        == 1;
  }

  /**
   * 競合する処理と利用範囲を調整するため、待機モデル呼び出しを確保する。
   *
   * <p>Reserve before the provider call; pending usage survives failure and restart.
   */
  public void reserveSleepModelCall(String project, long maxCalls, long maxTokens) {
    if (project == null || project.isBlank() || maxCalls < 0 || maxTokens < 0)
      throw new IllegalArgumentException("Invalid Sleep budget");
    transaction(
        () -> {
          db.sql("INSERT OR IGNORE INTO sleep_model_usage(project_id) VALUES(?)")
              .param(project)
              .update();
          int changed =
              db.sql(
                      """
          UPDATE sleep_model_usage SET calls=calls+1,pending=pending+?
          WHERE project_id=? AND calls<9223372036854775807
            AND (?=0 OR calls<?) AND (?=0 OR (unknown=0 AND pending=0 AND tokens<?))
          """)
                  .params(maxTokens > 0 ? 1 : 0, project, maxCalls, maxCalls, maxTokens, maxTokens)
                  .update();
          if (changed == 0) {
            var usage =
                db.sql(
                        "SELECT calls,tokens,pending,unknown FROM sleep_model_usage WHERE project_id=?")
                    .param(project)
                    .query(
                        (r, n) ->
                            new long[] {r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)})
                    .single();
            var reason =
                maxTokens > 0 && (usage[2] > 0 || usage[3] > 0)
                    ? dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                        .TOKEN_USAGE_UNKNOWN
                    : maxTokens > 0 && usage[1] >= maxTokens
                        ? dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                            .TOKEN_BUDGET_EXCEEDED
                        : dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
                            .LLM_CALL_BUDGET_EXCEEDED;
            throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(reason);
          }
          return null;
        });
  }

  /**
   * 後から経緯や使用量を確認できるよう、待機トークン数を記録する。
   *
   * <p>This accounting is separate from memory/checkpoint writes, also for previews.
   */
  public void recordSleepTokens(String project, Integer tokens) {
    boolean valid = tokens != null && tokens > 0;
    int changed =
        db.sql(
                """
        UPDATE sleep_model_usage SET pending=pending-1,
          tokens=CASE WHEN tokens>9223372036854775807-? THEN 9223372036854775807 ELSE tokens+? END,
          unknown=CASE WHEN ?=1 THEN unknown ELSE 1 END
        WHERE project_id=? AND pending>0
        """)
            .params(valid ? tokens : 0, valid ? tokens : 0, valid ? 1 : 0, project)
            .update();
    if (changed != 1) throw new IllegalStateException("No pending Sleep model call");
  }

  /** 後続の操作を進められるか判断するため、待機トークン予算を確認する。 */
  public void checkSleepTokenBudget(String project, long limit) {
    long tokens =
        db.sql("SELECT tokens FROM sleep_model_usage WHERE project_id=?")
            .param(project)
            .query(Long.class)
            .single();
    if (tokens > limit)
      throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
          dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason
              .TOKEN_BUDGET_EXCEEDED);
  }

  /** 長期記憶の保存と検索で用いる追加を返す。 */
  public LongTermMemory insert(MemoryCandidate c, String project, String session) {
    return insert(c, project, session, "mem_" + UUID.randomUUID(), true);
  }

  /** 長期記憶の保存と検索で用いる追加を返す。 */
  private LongTermMemory insert(
      MemoryCandidate c, String project, String session, String id, boolean includeTurns) {
    if (c.scope() == MemoryScope.PROJECT && (project == null || project.isBlank()))
      throw new IllegalArgumentException("Project required");
    return transaction(
        () -> {
          String now = OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
          db.sql(
                  """
          INSERT INTO memories(id,content,type,scope,status,confidence,created_at,updated_at,
            observed_at,project_id,summary,importance,valid_from,content_key)
          VALUES(?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?)
          """)
              .params(
                  id,
                  c.content(),
                  c.type().name(),
                  c.scope().name(),
                  c.confidence(),
                  now,
                  now,
                  now,
                  c.scope() == MemoryScope.PROJECT ? project : null,
                  c.summary(),
                  c.importance(),
                  now,
                  MemoryResolver.normalize(c.content()))
              .update();
          if (includeTurns) enrich(id, c, session);
          else enrichTags(id, c);
          index(id);
          return find(id).orElseThrow();
        });
  }

  /** 長期記憶の保存と検索に関するenrichを処理する。 */
  public void enrich(String id, MemoryCandidate c, String session) {
    for (String turn : c.sourceTurnIds())
      db.sql(
              """
        INSERT INTO memory_sources(memory_id,source,session_id,turn_id)
        SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM memory_sources WHERE memory_id=? AND session_id=? AND turn_id=?)
        """)
          .params(id, session + "/" + turn, session, turn, id, session, turn)
          .update();
    enrichTags(id, c);
  }

  /** 長期記憶の保存と検索に関するenrichタグ一覧を処理する。 */
  private void enrichTags(String id, MemoryCandidate c) {
    for (String tag : c.tags())
      db.sql(
              """
        INSERT INTO memory_tags(memory_id,tag) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM memory_tags WHERE memory_id=? AND tag=?)
        """)
          .params(id, tag, id, tag)
          .update();
    db.sql("UPDATE memories SET updated_at=? WHERE id=?").params(now(), id).update();
    index(id);
  }

  /**
   * 長期記憶の保存と検索で受け渡す振り返り確認の根拠を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param reflectionId 振り返り識別子
   * @param goalId 目標識別子
   * @param sessionId 会話の識別子
   * @param memoryId 記憶識別子
   * @param criteriaSha256 判断基準一覧内容の同一性を照合するSHA-256ハッシュ
   * @param verifiedAt 確認済み日時
   */
  public record ReflectionProof(
      String projectId,
      String reflectionId,
      String goalId,
      String sessionId,
      String memoryId,
      String criteriaSha256,
      java.time.Instant verifiedAt) {}

  /** 確認の根拠。 */
  private static final org.springframework.jdbc.core.RowMapper<ReflectionProof> PROOF =
      (r, n) ->
          new ReflectionProof(
              r.getString("project_id"),
              r.getString("reflection_id"),
              r.getString("goal_id"),
              r.getString("session_id"),
              r.getString("memory_id"),
              r.getString("criteria_sha256"),
              java.time.Instant.parse(r.getString("verified_at")));

  /** 長期記憶の保存と検索で用いる確認済み振り返り確認の根拠を返す。 */
  public Optional<ReflectionProof> verifiedReflectionProof(String project, String reflection) {
    return db.sql(
            "SELECT * FROM verified_reflection_memories WHERE project_id=? AND reflection_id=?")
        .params(project, reflection)
        .query(PROOF)
        .optional();
  }

  /** 長期記憶の保存と検索で用いる確認済み振り返り確認の根拠別記憶を返す。 */
  public Optional<ReflectionProof> verifiedReflectionProofByMemory(String project, String memory) {
    return db.sql("SELECT * FROM verified_reflection_memories WHERE project_id=? AND memory_id=?")
        .params(project, memory)
        .query(PROOF)
        .optional();
  }

  /**
   * 長期記憶の保存と検索で用いる追加確認済み振り返りを返す。
   *
   * <p>Atomic publication of a fact and its explicit non-conversation proof. Called after
   * independent verification.
   */
  public LongTermMemory insertVerifiedReflection(
      MemoryCandidate candidate,
      String project,
      String session,
      String reflection,
      String goal,
      String criteriaSha256,
      java.time.Instant verifiedAt) {
    if (candidate.scope() != MemoryScope.PROJECT
        || candidate.type() != MemoryType.PROJECT_STATE
        || project == null
        || project.isBlank()
        || session == null
        || session.isBlank()
        || reflection == null
        || reflection.isBlank()
        || goal == null
        || goal.isBlank()
        || criteriaSha256 == null
        || !criteriaSha256.matches("[a-f0-9]{64}")
        || verifiedAt == null)
      throw new IllegalArgumentException("Invalid verified reflection fact");
    return transaction(
        () -> {
          String memory = "mem_" + UUID.randomUUID();
          int inserted =
              db.sql("INSERT OR IGNORE INTO verified_reflection_memories VALUES(?,?,?,?,?,?,?)")
                  .params(
                      project,
                      reflection,
                      goal,
                      session,
                      memory,
                      criteriaSha256,
                      verifiedAt.toString())
                  .update();
          if (inserted == 0)
            return find(verifiedReflectionProof(project, reflection).orElseThrow().memoryId())
                .orElseThrow();
          return insert(candidate, project, session, memory, false);
        });
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  public void update(String id, MemoryCandidate c, String session) {
    db.sql(
            "UPDATE memories SET content=?,summary=?,confidence=?,importance=?,updated_at=?,content_key=? WHERE id=? AND status='ACTIVE'")
        .params(
            c.content(),
            c.summary(),
            c.confidence(),
            c.importance(),
            now(),
            MemoryResolver.normalize(c.content()),
            id)
        .update();
    enrich(id, c, session);
  }

  /** 長期記憶の保存と検索に関するsupersedeを処理する。 */
  public void supersede(String oldId, String replacement, String relation) {
    db.sql(
            "UPDATE memories SET status='SUPERSEDED',superseded_by=?,valid_until=?,updated_at=? WHERE id=? AND status='ACTIVE'")
        .params(replacement, now(), now(), oldId)
        .update();
    relate(oldId, replacement, relation);
  }

  /** 長期記憶の保存と検索に関するrelateを処理する。 */
  public void relate(String from, String to, String type) {
    db.sql("INSERT INTO memory_relations(from_memory_id,to_memory_id,relation_type) VALUES(?,?,?)")
        .params(from, to, type)
        .update();
  }

  /** 長期記憶の保存と検索で用いる関連一覧を返す。 */
  public List<String> relations(String id) {
    return db.sql(
            "SELECT from_memory_id || ' ' || relation_type || ' ' || to_memory_id FROM memory_relations WHERE from_memory_id=? OR to_memory_id=?")
        .params(id, id)
        .query(String.class)
        .list();
  }

  /** 長期記憶の保存と検索に関する複製取得元一覧を処理する。 */
  public void copySources(String from, String to) {
    for (var s : find(from).orElseThrow().sources())
      db.sql(
              """
        INSERT INTO memory_sources(memory_id,source,session_id,turn_id)
        SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM memory_sources WHERE memory_id=? AND session_id=? AND turn_id=?)
        """)
          .params(
              to,
              s.sessionId() + "/" + s.turnId(),
              s.sessionId(),
              s.turnId(),
              to,
              s.sessionId(),
              s.turnId())
          .update();
  }

  /** 長期記憶の保存と検索に関する保管を処理する。 */
  public void archive(String id) {
    db.sql("UPDATE memories SET status='ARCHIVED',updated_at=? WHERE id=?")
        .params(now(), id)
        .update();
  }

  /** 長期記憶の保存と検索に関するaccessedを処理する。 */
  public void accessed(String id) {
    db.sql("UPDATE memories SET last_accessed_at=? WHERE id=?").params(now(), id).update();
  }

  /** 対象を特定できるよう、記憶保存と検索の窓口を探す。 */
  public Optional<LongTermMemory> find(String id) {
    return db.sql("SELECT * FROM memories WHERE id=? AND summary IS NOT NULL")
        .param(id)
        .query(this::map)
        .optional();
  }

  /** 長期記憶の保存と検索で用いる完全一致を返す。 */
  public Optional<LongTermMemory> exact(MemoryCandidate candidate, String project) {
    return db.sql(
            "SELECT * FROM memories WHERE status='ACTIVE' AND summary IS NOT NULL AND scope=? AND coalesce(project_id,'')=? AND content_key=?")
        .params(
            candidate.scope().name(),
            candidate.scope() == MemoryScope.GLOBAL ? "" : project,
            MemoryResolver.normalize(candidate.content()))
        .query(this::map)
        .optional();
  }

  /** 表示対象。 */
  private static final String VISIBLE =
      "summary IS NOT NULL AND (scope='GLOBAL' OR (scope='PROJECT' AND project_id=?))";

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<LongTermMemory> list(String project, int limit, int offset) {
    return db.sql(
            "SELECT * FROM memories WHERE "
                + VISIBLE
                + " AND status='ACTIVE' ORDER BY updated_at DESC,id LIMIT ? OFFSET ?")
        .params(project, Math.max(1, Math.min(1000, limit)), Math.max(0, offset))
        .query(this::map)
        .list();
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  public List<LongTermMemory> search(String query, String project, int limit) {
    if (query == null || query.isBlank()) return List.of();
    var terms = MemorySearchTerms.of(query);
    if (terms.isEmpty()) return List.of();
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>();
    args.add(project);
    // FTS5 trigram works for Japanese substrings too. Short terms use escaped LIKE.
    for (String term : terms) {
      if (term.codePointCount(0, term.length()) >= 3) {
        clauses.add(
            "id IN (SELECT memory_id FROM long_term_memory_fts WHERE long_term_memory_fts MATCH ?)");
        args.add("\"" + term.replace("\"", "\"\"") + "\"");
      } else {
        clauses.add(
            "(lower(content || ' ' || summary || ' ' || coalesce((SELECT group_concat(tag,' ') FROM memory_tags WHERE memory_id=memories.id),'')) LIKE ? ESCAPE '\\')");
        args.add("%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
      }
    }
    args.add(Math.max(1, Math.min(1000, limit)));
    return db.sql(
            "SELECT * FROM memories WHERE "
                + VISIBLE
                + " AND status='ACTIVE' AND ("
                + String.join(" OR ", clauses)
                + ") ORDER BY importance*confidence DESC,updated_at DESC,id LIMIT ?")
        .params(args)
        .query(this::map)
        .list();
  }

  /** 長期記憶の保存と検索で用いる件数を返す。 */
  public Map<String, Long> counts(String project) {
    var result = new LinkedHashMap<String, Long>();
    for (String scope : List.of("GLOBAL", "PROJECT"))
      for (String status : List.of("ACTIVE", "SUPERSEDED", "ARCHIVED"))
        result.put(
            scope + " " + status,
            db.sql("SELECT count(*) FROM memories WHERE " + VISIBLE + " AND scope=? AND status=?")
                .params(project, scope, status)
                .query(Long.class)
                .single());
    return result;
  }

  /** 長期記憶の保存と検索で用いる直近処理済みを返す。 */
  public long lastProcessed(String session) {
    return db.sql(
            "SELECT coalesce(max(to_sequence),0) FROM sleep_runs WHERE session_id=? AND status='COMPLETED'")
        .param(session)
        .query(Long.class)
        .single();
  }

  /** 後から参照できるよう、実行を保存する。 */
  public void saveRun(SleepRun r) {
    db.sql("INSERT INTO sleep_runs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .params(
            r.id(),
            r.sessionId(),
            r.projectId(),
            r.startedAt(),
            r.completedAt(),
            r.status(),
            r.fromSequence(),
            r.toSequence(),
            r.processedTurns(),
            r.candidateCount(),
            r.added(),
            r.updated(),
            r.merged(),
            r.superseded(),
            r.ignored(),
            r.conflicts(),
            r.failed())
        .update();
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  public List<SleepRun> history(String project, int limit) {
    return db.sql("SELECT * FROM sleep_runs WHERE project_id=? ORDER BY completed_at DESC LIMIT ?")
        .params(project, Math.max(1, Math.min(limit, 100)))
        .query(
            (r, n) ->
                new SleepRun(
                    r.getString("id"),
                    r.getString("session_id"),
                    r.getString("project_id"),
                    r.getString("started_at"),
                    r.getString("completed_at"),
                    r.getString("status"),
                    r.getLong("from_sequence"),
                    r.getLong("to_sequence"),
                    r.getInt("processed_turns"),
                    r.getInt("candidate_count"),
                    r.getInt("added"),
                    r.getInt("updated"),
                    r.getInt("merged"),
                    r.getInt("superseded"),
                    r.getInt("ignored"),
                    r.getInt("conflicts"),
                    r.getInt("failed")))
        .list();
  }

  /** 長期記憶の保存と検索に関する位置を処理する。 */
  private void index(String id) {
    db.sql("DELETE FROM long_term_memory_fts WHERE memory_id=?").param(id).update();
    db.sql(
            "INSERT INTO long_term_memory_fts SELECT id,content,summary,coalesce((SELECT group_concat(tag,' ') FROM memory_tags WHERE memory_id=?),'') FROM memories WHERE id=? AND summary IS NOT NULL")
        .params(id, id)
        .update();
    db.sql("DELETE FROM memory_fts WHERE memory_id=?").param(id).update();
    db.sql("INSERT INTO memory_fts SELECT id,content FROM memories WHERE id=?").param(id).update();
  }

  /** 長期記憶の保存と検索で用いる対応表を返す。 */
  private LongTermMemory map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    String id = r.getString("id");
    return new LongTermMemory(
        id,
        MemoryScope.valueOf(r.getString("scope")),
        r.getString("project_id"),
        MemoryType.valueOf(r.getString("type")),
        r.getString("content"),
        r.getString("summary"),
        r.getDouble("confidence"),
        r.getDouble("importance"),
        MemoryStatus.valueOf(r.getString("status")),
        time(r.getString("created_at")),
        time(r.getString("updated_at")),
        time(r.getString("last_accessed_at")),
        time(r.getString("valid_from")),
        time(r.getString("valid_until")),
        r.getString("superseded_by"),
        db.sql(
                "SELECT DISTINCT session_id,turn_id FROM memory_sources WHERE memory_id=? AND session_id IS NOT NULL ORDER BY session_id,turn_id")
            .param(id)
            .query((s, i) -> new MemorySource(s.getString(1), s.getString(2)))
            .list(),
        db.sql("SELECT DISTINCT tag FROM memory_tags WHERE memory_id=? ORDER BY tag")
            .param(id)
            .query(String.class)
            .list());
  }

  /** 長期記憶の保存と検索で用いる時刻を返す。 */
  private static OffsetDateTime time(String value) {
    return value == null ? null : OffsetDateTime.parse(value);
  }

  /** 長期記憶の保存と検索で用いる現在日時を返す。 */
  private static String now() {
    return OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
  }
}
