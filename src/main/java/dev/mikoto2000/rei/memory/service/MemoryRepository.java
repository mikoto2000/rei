package dev.mikoto2000.rei.memory.service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import dev.mikoto2000.rei.memory.model.*;

/** Additive migration and transactional persistence on the existing memory DataSource. */
@Repository
public class MemoryRepository {
  private final JdbcClient db;
  private final TransactionTemplate transactions;
  public MemoryRepository(@Qualifier("memoryConsolidationDataSource") DataSource source, MemoryService legacy) {
    db = JdbcClient.create(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction(() -> {
      column("memories", "project_id", "TEXT");
      column("memories", "summary", "TEXT");
      column("memories", "importance", "REAL NOT NULL DEFAULT 0.5");
      column("memories", "last_accessed_at", "TEXT");
      column("memories", "valid_from", "TEXT");
      column("memories", "superseded_by", "TEXT");
      column("memories", "content_key", "TEXT");
      column("memory_sources", "session_id", "TEXT");
      column("memory_sources", "turn_id", "TEXT");
      db.sql("CREATE INDEX IF NOT EXISTS memory_scope_status ON memories(scope, project_id, status)").update();
      for(var row:db.sql("SELECT id,content FROM memories WHERE summary IS NOT NULL AND content_key IS NULL")
          .query((r,n)->Map.entry(r.getString("id"),r.getString("content"))).list())
        db.sql("UPDATE memories SET content_key=? WHERE id=?").params(MemoryResolver.normalize(row.getValue()),row.getKey()).update();
      db.sql("CREATE UNIQUE INDEX IF NOT EXISTS active_memory_content ON memories(scope,coalesce(project_id,''),content_key) WHERE status='ACTIVE' AND content_key IS NOT NULL").update();
      db.sql("CREATE VIRTUAL TABLE IF NOT EXISTS long_term_memory_fts USING fts5(memory_id UNINDEXED, content, summary, tags, tokenize='trigram')").update();
      db.sql("""
          CREATE TABLE IF NOT EXISTS sleep_runs (
            id TEXT PRIMARY KEY, session_id TEXT NOT NULL, project_id TEXT NOT NULL,
            started_at TEXT NOT NULL, completed_at TEXT NOT NULL, status TEXT NOT NULL,
            from_sequence INTEGER NOT NULL, to_sequence INTEGER NOT NULL, processed_turns INTEGER NOT NULL,
            candidate_count INTEGER NOT NULL, added INTEGER NOT NULL, updated INTEGER NOT NULL,
            merged INTEGER NOT NULL, superseded INTEGER NOT NULL, ignored INTEGER NOT NULL,
            conflicts INTEGER NOT NULL, failed INTEGER NOT NULL)
          """).update();
      db.sql("CREATE TABLE IF NOT EXISTS sleep_model_usage (project_id TEXT PRIMARY KEY, calls INTEGER NOT NULL DEFAULT 0, tokens INTEGER NOT NULL DEFAULT 0, pending INTEGER NOT NULL DEFAULT 0, unknown INTEGER NOT NULL DEFAULT 0)").update();
      return null;
    });
  }
  private void column(String table, String name, String definition) {
    if (!db.sql("PRAGMA table_info(" + table + ")").query((r,n) -> r.getString("name")).list().contains(name))
      db.sql("ALTER TABLE " + table + " ADD COLUMN " + name + " " + definition).update();
  }
  public <T> T transaction(Supplier<T> action) { return transactions.execute(status -> action.get()); }
  /** Reserve before the provider call; pending usage survives failure and restart. */
  public void reserveSleepModelCall(String project,long maxCalls,long maxTokens) {
    if(project==null||project.isBlank()||maxCalls<0||maxTokens<0)throw new IllegalArgumentException("Invalid Sleep budget");
    transaction(()->{
      db.sql("INSERT OR IGNORE INTO sleep_model_usage(project_id) VALUES(?)").param(project).update();
      int changed=db.sql("""
          UPDATE sleep_model_usage SET calls=calls+1,pending=pending+?
          WHERE project_id=? AND calls<9223372036854775807
            AND (?=0 OR calls<?) AND (?=0 OR (unknown=0 AND pending=0 AND tokens<?))
          """).params(maxTokens>0?1:0,project,maxCalls,maxCalls,maxTokens,maxTokens).update();
      if(changed==0) {
        var usage=db.sql("SELECT calls,tokens,pending,unknown FROM sleep_model_usage WHERE project_id=?")
            .param(project).query((r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)}).single();
        var reason=maxTokens>0&&(usage[2]>0||usage[3]>0)
            ?dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_USAGE_UNKNOWN
            :maxTokens>0&&usage[1]>=maxTokens
                ?dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED
                :dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.LLM_CALL_BUDGET_EXCEEDED;
        throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(reason);
      }
      return null;
    });
  }
  /** This accounting is separate from memory/checkpoint writes, also for previews. */
  public void recordSleepTokens(String project,Integer tokens) {
    boolean valid=tokens!=null&&tokens>=0;
    int changed=db.sql("""
        UPDATE sleep_model_usage SET pending=pending-1,
          tokens=CASE WHEN tokens>9223372036854775807-? THEN 9223372036854775807 ELSE tokens+? END,
          unknown=CASE WHEN ?=1 THEN unknown ELSE 1 END
        WHERE project_id=? AND pending>0
        """).params(valid?tokens:0,valid?tokens:0,valid?1:0,project).update();
    if(changed!=1)throw new IllegalStateException("No pending Sleep model call");
  }
  public void checkSleepTokenBudget(String project,long limit) {
    long tokens=db.sql("SELECT tokens FROM sleep_model_usage WHERE project_id=?").param(project).query(Long.class).single();
    if(tokens>limit)throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
        dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED);
  }
  public LongTermMemory insert(MemoryCandidate c, String project, String session) {
    if (c.scope() == MemoryScope.PROJECT && (project == null || project.isBlank())) throw new IllegalArgumentException("Project required");
    return transaction(() -> {
      String id = "mem_" + UUID.randomUUID();
      String now = OffsetDateTime.now(java.time.ZoneOffset.UTC).toString();
      db.sql("""
          INSERT INTO memories(id,content,type,scope,status,confidence,created_at,updated_at,
            observed_at,project_id,summary,importance,valid_from,content_key)
          VALUES(?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?,?)
          """).params(id,c.content(),c.type().name(),c.scope().name(),c.confidence(),now,now,now,
              c.scope()==MemoryScope.PROJECT ? project : null,c.summary(),c.importance(),now,MemoryResolver.normalize(c.content())).update();
      enrich(id,c,session);
      index(id);
      return find(id).orElseThrow();
    });
  }
  public void enrich(String id, MemoryCandidate c, String session) {
    for (String turn : c.sourceTurnIds()) db.sql("""
        INSERT INTO memory_sources(memory_id,source,session_id,turn_id)
        SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM memory_sources WHERE memory_id=? AND session_id=? AND turn_id=?)
        """).params(id,session + "/" + turn,session,turn,id,session,turn).update();
    for (String tag : c.tags()) db.sql("""
        INSERT INTO memory_tags(memory_id,tag) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM memory_tags WHERE memory_id=? AND tag=?)
        """).params(id,tag,id,tag).update();
    db.sql("UPDATE memories SET updated_at=? WHERE id=?").params(now(),id).update();
    index(id);
  }
  public void update(String id, MemoryCandidate c, String session) {
    db.sql("UPDATE memories SET content=?,summary=?,confidence=?,importance=?,updated_at=?,content_key=? WHERE id=? AND status='ACTIVE'")
        .params(c.content(),c.summary(),c.confidence(),c.importance(),now(),MemoryResolver.normalize(c.content()),id).update();
    enrich(id,c,session);
  }
  public void supersede(String oldId, String replacement, String relation) {
    db.sql("UPDATE memories SET status='SUPERSEDED',superseded_by=?,valid_until=?,updated_at=? WHERE id=? AND status='ACTIVE'")
        .params(replacement,now(),now(),oldId).update();
    relate(oldId,replacement,relation);
  }
  public void relate(String from, String to, String type) {
    db.sql("INSERT INTO memory_relations(from_memory_id,to_memory_id,relation_type) VALUES(?,?,?)").params(from,to,type).update();
  }
  public List<String> relations(String id) {
    return db.sql("SELECT from_memory_id || ' ' || relation_type || ' ' || to_memory_id FROM memory_relations WHERE from_memory_id=? OR to_memory_id=?")
        .params(id,id).query(String.class).list();
  }
  public void copySources(String from, String to) {
    for (var s : find(from).orElseThrow().sources()) db.sql("""
        INSERT INTO memory_sources(memory_id,source,session_id,turn_id)
        SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM memory_sources WHERE memory_id=? AND session_id=? AND turn_id=?)
        """).params(to,s.sessionId()+"/"+s.turnId(),s.sessionId(),s.turnId(),to,s.sessionId(),s.turnId()).update();
  }
  public void archive(String id) { db.sql("UPDATE memories SET status='ARCHIVED',updated_at=? WHERE id=?").params(now(),id).update(); }
  public void accessed(String id) { db.sql("UPDATE memories SET last_accessed_at=? WHERE id=?").params(now(),id).update(); }
  public Optional<LongTermMemory> find(String id) {
    return db.sql("SELECT * FROM memories WHERE id=? AND summary IS NOT NULL").param(id).query(this::map).optional();
  }
  public Optional<LongTermMemory> exact(MemoryCandidate candidate,String project) {
    return db.sql("SELECT * FROM memories WHERE status='ACTIVE' AND summary IS NOT NULL AND scope=? AND coalesce(project_id,'')=? AND content_key=?")
        .params(candidate.scope().name(),candidate.scope()==MemoryScope.GLOBAL?"":project,MemoryResolver.normalize(candidate.content()))
        .query(this::map).optional();
  }
  private static final String VISIBLE = "summary IS NOT NULL AND (scope='GLOBAL' OR (scope='PROJECT' AND project_id=?))";
  public List<LongTermMemory> list(String project, int limit, int offset) {
    return db.sql("SELECT * FROM memories WHERE " + VISIBLE + " AND status='ACTIVE' ORDER BY updated_at DESC,id LIMIT ? OFFSET ?")
        .params(project,Math.max(1,Math.min(1000,limit)),Math.max(0,offset)).query(this::map).list();
  }
  public List<LongTermMemory> search(String query, String project, int limit) {
    if (query == null || query.isBlank()) return List.of();
    var terms = MemorySearchTerms.of(query);
    if (terms.isEmpty()) return List.of();
    var clauses = new ArrayList<String>();
    var args = new ArrayList<Object>(); args.add(project);
    // FTS5 trigram works for Japanese substrings too. Short terms use escaped LIKE.
    for (String term : terms) {
      if (term.codePointCount(0,term.length()) >= 3) {
        clauses.add("id IN (SELECT memory_id FROM long_term_memory_fts WHERE long_term_memory_fts MATCH ?)");
        args.add("\"" + term.replace("\"","\"\"") + "\"");
      } else {
        clauses.add("(lower(content || ' ' || summary || ' ' || coalesce((SELECT group_concat(tag,' ') FROM memory_tags WHERE memory_id=memories.id),'')) LIKE ? ESCAPE '\\')");
        args.add("%"+term.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");
      }
    }
    args.add(Math.max(1,Math.min(1000,limit)));
    return db.sql("SELECT * FROM memories WHERE " + VISIBLE + " AND status='ACTIVE' AND ("
        + String.join(" OR ",clauses) + ") ORDER BY importance*confidence DESC,updated_at DESC,id LIMIT ?")
        .params(args).query(this::map).list();
  }
  public Map<String,Long> counts(String project) {
    var result = new LinkedHashMap<String,Long>();
    for (String scope : List.of("GLOBAL","PROJECT")) for (String status : List.of("ACTIVE","SUPERSEDED","ARCHIVED"))
      result.put(scope+" "+status, db.sql("SELECT count(*) FROM memories WHERE " + VISIBLE + " AND scope=? AND status=?")
          .params(project,scope,status).query(Long.class).single());
    return result;
  }
  public long lastProcessed(String session) {
    return db.sql("SELECT coalesce(max(to_sequence),0) FROM sleep_runs WHERE session_id=? AND status='COMPLETED'")
        .param(session).query(Long.class).single();
  }
  public void saveRun(SleepRun r) {
    db.sql("INSERT INTO sleep_runs VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .params(r.id(),r.sessionId(),r.projectId(),r.startedAt(),r.completedAt(),r.status(),r.fromSequence(),r.toSequence(),
            r.processedTurns(),r.candidateCount(),r.added(),r.updated(),r.merged(),r.superseded(),r.ignored(),r.conflicts(),r.failed()).update();
  }
  public List<SleepRun> history(String project, int limit) {
    return db.sql("SELECT * FROM sleep_runs WHERE project_id=? ORDER BY completed_at DESC LIMIT ?")
        .params(project,Math.max(1,Math.min(limit,100))).query((r,n) -> new SleepRun(r.getString("id"),r.getString("session_id"),
            r.getString("project_id"),r.getString("started_at"),r.getString("completed_at"),r.getString("status"),
            r.getLong("from_sequence"),r.getLong("to_sequence"),r.getInt("processed_turns"),r.getInt("candidate_count"),
            r.getInt("added"),r.getInt("updated"),r.getInt("merged"),r.getInt("superseded"),r.getInt("ignored"),r.getInt("conflicts"),r.getInt("failed"))).list();
  }
  private void index(String id) {
    db.sql("DELETE FROM long_term_memory_fts WHERE memory_id=?").param(id).update();
    db.sql("INSERT INTO long_term_memory_fts SELECT id,content,summary,coalesce((SELECT group_concat(tag,' ') FROM memory_tags WHERE memory_id=?),'') FROM memories WHERE id=? AND summary IS NOT NULL")
        .params(id,id).update();
    db.sql("DELETE FROM memory_fts WHERE memory_id=?").param(id).update();
    db.sql("INSERT INTO memory_fts SELECT id,content FROM memories WHERE id=?").param(id).update();
  }
  private LongTermMemory map(java.sql.ResultSet r, int n) throws java.sql.SQLException {
    String id=r.getString("id");
    return new LongTermMemory(id,MemoryScope.valueOf(r.getString("scope")),r.getString("project_id"),MemoryType.valueOf(r.getString("type")),
        r.getString("content"),r.getString("summary"),r.getDouble("confidence"),r.getDouble("importance"),MemoryStatus.valueOf(r.getString("status")),
        time(r.getString("created_at")),time(r.getString("updated_at")),time(r.getString("last_accessed_at")),
        time(r.getString("valid_from")),time(r.getString("valid_until")),r.getString("superseded_by"),
        db.sql("SELECT DISTINCT session_id,turn_id FROM memory_sources WHERE memory_id=? AND session_id IS NOT NULL ORDER BY session_id,turn_id")
            .param(id).query((s,i) -> new MemorySource(s.getString(1),s.getString(2))).list(),
        db.sql("SELECT DISTINCT tag FROM memory_tags WHERE memory_id=? ORDER BY tag").param(id).query(String.class).list());
  }
  private static OffsetDateTime time(String value) { return value==null?null:OffsetDateTime.parse(value); }
  private static String now() { return OffsetDateTime.now(java.time.ZoneOffset.UTC).toString(); }
}
