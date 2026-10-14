package dev.mikoto2000.rei.checkpoint;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Repository
public class PersistentCheckpointRepository {
  private final JdbcClient db;
  private final TransactionTemplate transaction;
  private final CheckpointProperties settings;
  private dev.mikoto2000.rei.storage.StorageObjectRegistry objects;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setStorageObjectRegistry(dev.mikoto2000.rei.storage.StorageObjectRegistry objects){this.objects=objects;}
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
  static final String OWNER=ProcessHandle.current().pid()+"@"+ProcessHandle.current().info().startInstant().orElseThrow();
  public PersistentCheckpointRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,CheckpointProperties settings) {
    this.settings=settings;db=JdbcClient.create(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    // An update obtains SQLite's writer lock before reads, avoiding read-to-write upgrade races.
    db.sql("CREATE TABLE IF NOT EXISTS checkpoint_heads(project TEXT NOT NULL,task TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 0,owner TEXT,lease_run TEXT,PRIMARY KEY(project,task))").update();
    db.sql("CREATE TABLE IF NOT EXISTS checkpoint_revisions(project TEXT NOT NULL,task TEXT NOT NULL,revision INTEGER NOT NULL,event TEXT NOT NULL,snapshot TEXT NOT NULL,created TEXT NOT NULL,PRIMARY KEY(project,task,revision),UNIQUE(project,task,event))").update();
    db.sql("CREATE TABLE IF NOT EXISTS checkpoint_event_keys(project TEXT NOT NULL,task TEXT NOT NULL,event TEXT NOT NULL,PRIMARY KEY(project,task,event))").update();
    db.sql("INSERT OR IGNORE INTO checkpoint_event_keys(project,task,event) SELECT project,task,event FROM checkpoint_revisions").update();
  }
  public PersistentCheckpoint get(String project,String task) {
    RuntimeException invalid=null;
    try(var rows=rows(project,task)){var iterator=rows.iterator();while(iterator.hasNext())try{return decode(iterator.next(),project,task);}catch(RuntimeException e){invalid=e;}}
    if(invalid!=null)throw invalid;
    throw new IllegalArgumentException("Checkpoint task not found");
  }
  public List<String> diagnostics(String project,String task) {
    var warnings=new ArrayList<String>();
    try(var rows=rows(project,task)){var iterator=rows.iterator();while(iterator.hasNext())try{decode(iterator.next(),project,task);break;}catch(RuntimeException error){warnings.add(error.getMessage());}}
    return List.copyOf(warnings);
  }
  public List<PersistentCheckpoint> list(String project) {
    return db.sql("SELECT task FROM checkpoint_heads WHERE project=? AND revision>0 ORDER BY task LIMIT 1000").param(project).query(String.class).list().stream().map(t->get(project,t)).toList();
  }
  public record OverviewSelection(long count,ResumeOverview.Candidate latest) {}
  private static final String OVERVIEW_HEADS="""
      WITH heads AS MATERIALIZED (
        SELECT h.task,h.revision AS head_revision,h.owner,r.created,
          CASE WHEN json_valid(r.snapshot) THEN r.snapshot END AS snapshot
        FROM checkpoint_heads h LEFT JOIN checkpoint_revisions r
          ON r.project=h.project AND r.task=h.task AND r.revision=h.revision
        WHERE h.project=:project AND h.revision>0
      )
      """;
  private static final String VALID_OVERVIEW_HEAD="""
      snapshot IS NOT NULL AND json_extract(snapshot,'$.schemaVersion')=1
      AND json_extract(snapshot,'$.projectId')=:project AND json_extract(snapshot,'$.taskId')=task
      AND json_extract(snapshot,'$.revision')=head_revision
      AND (json_type(snapshot,'$.createdAt') IN ('integer','real') OR datetime(json_extract(snapshot,'$.createdAt')) IS NOT NULL)
      AND json_type(snapshot,'$.request')='text' AND json_type(snapshot,'$.status')='text'
      AND json_type(snapshot,'$.projectRoot')='text' AND json_type(snapshot,'$.sessionId')='text'
      AND json_type(snapshot,'$.originalRunId')='text' AND json_type(snapshot,'$.runId')='text'
      AND json_type(snapshot,'$.instructions')='array' AND json_type(snapshot,'$.acceptanceCriteria')='array'
      AND json_type(snapshot,'$.plan')='array' AND json_type(snapshot,'$.evidence')='array'
      AND json_type(snapshot,'$.operations')='array' AND json_type(snapshot,'$.files')='object'
      AND json_type(snapshot,'$.git')='object' AND json_type(snapshot,'$.processes')='array'
      AND json_type(snapshot,'$.blockers')='array'
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot) WHERE key NOT IN (
        'schemaVersion','revision','createdAt','taskId','projectId','projectRoot','sessionId','originalRunId','runId',
        'resumedFromRunId','resumedFromRevision','request','instructions','acceptanceCriteria','plan','currentStep',
        'status','interruptionReason','nextAction','evidence','operations','files','git','processes','blockers','reconciliation','mode','voiceInput','progress'))
      AND COALESCE(json_extract(snapshot,'$.mode'),'EXCLUSIVE') IN ('EXCLUSIVE','READ_ONLY','CONVERSATION')
      AND COALESCE(json_type(snapshot,'$.nextAction'),'null') IN ('text','null')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.instructions') WHERE type<>'text')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.acceptanceCriteria') WHERE type<>'text')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.blockers') WHERE type<>'text')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.files') WHERE type<>'text')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.git') WHERE type<>'text')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.evidence') WHERE type<>'object')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.plan') WHERE type<>'object')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.processes') WHERE type<>'object')
      AND NOT EXISTS(SELECT 1 FROM json_each(snapshot,'$.operations')
        WHERE type<>'object' OR COALESCE(json_extract(CASE WHEN type='object' THEN value END,'$.status'),'PLANNED')
          NOT IN ('PLANNED','STARTED','SUCCEEDED','FAILED','UNKNOWN'))
      """;
  /** Normal heads are projected in SQLite; only damaged heads need the existing decoder/fallback. */
  private JdbcClient.StatementSpec overviewQuery(String project,Instant failedCutoff,boolean latestOnly,boolean explain) {
    var fallbacks=new LinkedHashMap<String,String>();
    var damaged=db.sql(OVERVIEW_HEADS+"SELECT task FROM heads WHERE NOT COALESCE(("+VALID_OVERVIEW_HEAD+"),0)")
        .param("project",project).query(String.class).list();
    for(String task:damaged) {
      try {
        var recovered=get(project,task);var fields=fields(recovered);
        fields.put("createdAt",recovered.createdAt().toString());
        fallbacks.put(task,json.writeValueAsString(fields));
      }
      catch(RuntimeException|java.io.IOException invalid){
        // No valid history: keep a read-only review candidate rather than imply completion.
        fallbacks.put(task,"{\"revision\":0,\"status\":\"UNAVAILABLE\"}");
      }
    }
    var live=db.sql("SELECT DISTINCT owner FROM checkpoint_heads WHERE project=? AND owner IS NOT NULL")
        .param(project).query(String.class).list().stream().filter(PersistentCheckpointRepository::alive).toList();
    String sql=OVERVIEW_HEADS+"""
        , restored AS MATERIALIZED (
          SELECT *,COALESCE((SELECT value FROM json_each(:fallbacks) WHERE key=task),snapshot) AS state,
            EXISTS(SELECT 1 FROM json_each(:fallbacks) WHERE key=task) AS fallback
          FROM heads
        ), projected AS MATERIALIZED (
          SELECT task,head_revision,owner,created,fallback,
            json_extract(state,'$.revision') AS revision,
            CASE WHEN fallback THEN COALESCE(json_extract(state,'$.createdAt'),created) ELSE created END AS saved_at,
            substr(created,1,19)||substr(CASE WHEN substr(created,20,1)='.' THEN substr(created,21,length(created)-21) ELSE '' END||'000000000',1,9) AS saved_sort,
            json_extract(state,'$.status') AS status,
            EXISTS(SELECT 1 FROM json_each(state,'$.operations') WHERE json_extract(value,'$.status')='UNKNOWN'
              OR (json_extract(state,'$.status')='RUNNING' AND json_extract(value,'$.status')='STARTED')) AS unknown,
            state
          FROM restored
        ), candidates AS MATERIALIZED (
          SELECT * FROM projected WHERE status NOT IN ('ABANDONED','CANCELLED')
            AND (status IN ('INTERRUPTED','WAITING','WAITING_APPROVAL','UNAVAILABLE') OR unknown OR fallback
              OR (status='FAILED' AND saved_sort>=:cutoff)
              OR (status='RUNNING' AND NOT EXISTS(SELECT 1 FROM json_each(:live) WHERE value=owner)))
            AND NOT (status='RUNNING' AND EXISTS(SELECT 1 FROM json_each(:live) WHERE value=owner))
        )
        SELECT task,revision,head_revision,saved_at,status,fallback,unknown,
          json_extract(state,'$.request') AS request,json_extract(state,'$.nextAction') AS remaining,
          COALESCE((SELECT json_extract(value,'$.description') FROM json_each(state,'$.plan')
            WHERE json_extract(value,'$.status')='DONE' ORDER BY key DESC LIMIT 1),
            (SELECT json_extract(value,'$.summary')||'（保存された申告）' FROM json_each(state,'$.evidence')
              WHERE json_extract(value,'$.origin')='ASSISTANT_CLAIM' ORDER BY key DESC LIMIT 1)) AS completed,
          COUNT(*) OVER () AS total
        FROM candidates ORDER BY saved_sort DESC,task
        """+(latestOnly?" LIMIT 1":"");
    String cutoff=java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC).format(failedCutoff)
        +String.format(Locale.ROOT,"%09d",failedCutoff.getNano());
    try{return db.sql((explain?"EXPLAIN QUERY PLAN ":"")+sql).param("project",project).param("cutoff",cutoff)
        .param("fallbacks",json.writeValueAsString(fallbacks)).param("live",json.writeValueAsString(live));}
    catch(java.io.IOException error){throw new IllegalStateException(error);}
  }
  private ResumeOverview.Candidate overviewCandidate(java.sql.ResultSet row) throws java.sql.SQLException {
    boolean fallback=row.getBoolean("fallback"),unknown=row.getBoolean("unknown");
    String status=row.getString("status");
    String notice="UNAVAILABLE".equals(status)?"正常revisionが見つかりません。保存状態は未確認、要確認":
        fallback?"新しいrevisionが破損・非対応。過去の正常revisionは参考情報、要確認"+(unknown?"。結果不明の操作あり":""):
        unknown?"結果不明の操作あり・要確認。再実行前の照合が必要":
        "RUNNING".equals(status)?"実行所有権喪失・中断候補。再開前の照合が必要":"再開前の照合が必要";
    return new ResumeOverview.Candidate(row.getString("task"),row.getLong("revision"),row.getLong("head_revision"),
        overviewInstant(row.getString("saved_at")),"UNAVAILABLE".equals(status)?"未記録":ResumeOverview.safe(status,24),ResumeOverview.safe(row.getString("request"),80),
        ResumeOverview.safe(row.getString("completed"),120),ResumeOverview.safe(row.getString("remaining"),120),notice,fallback);
  }
  private Instant overviewInstant(String value) {
    if(value.matches("-?\\d+(?:\\.\\d+)?")) {
      var seconds=new java.math.BigDecimal(value);
      long whole=seconds.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();
      return Instant.ofEpochSecond(whole,seconds.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).longValue());
    }
    return Instant.parse(value);
  }
  public OverviewSelection overviewSelection(String project,Instant cutoff) {
    // A deferred read transaction keeps damage, lease and candidate reads on one SQLite snapshot.
    return transaction.execute(tx->overviewQuery(project,cutoff,true,false)
        .query((row,n)->new OverviewSelection(row.getLong("total"),overviewCandidate(row)))
        .optional().orElse(new OverviewSelection(0,null)));
  }
  List<ResumeOverview.Candidate> overviewCandidates(String project,Instant cutoff) {
    return transaction.execute(tx->overviewQuery(project,cutoff,false,false).query((row,n)->overviewCandidate(row)).list());
  }
  List<String> overviewQueryPlan(String project,Instant cutoff) {
    return overviewQuery(project,cutoff,true,true).query((row,n)->row.getString("detail")).list();
  }
  /** Page immutable original Run identities, including a valid older revision of a damaged head. */
  public List<PersistentCheckpoint> taskPage(String project,String root,String session,String after,int limit) {
    if(limit<1||limit>101)throw new IllegalArgumentException("Invalid projection page limit");
    return db.sql("""
        WITH valid AS (SELECT task,CASE WHEN json_valid(snapshot) THEN snapshot END AS snapshot
          FROM checkpoint_revisions WHERE project=:project)
        SELECT task,MIN(json_extract(snapshot,'$.originalRunId')) AS original FROM valid
        WHERE json_extract(snapshot,'$.projectRoot')=:root AND (:session IS NULL OR json_extract(snapshot,'$.sessionId')=:session)
        GROUP BY task HAVING original>:after ORDER BY original,task LIMIT :limit
        """).param("project",project).param("root",root).param("session",session).param("after",after).param("limit",limit)
        .query((rs,n)->rs.getString("task")).list().stream().map(task->get(project,task)).toList();
  }
  public Optional<PersistentCheckpoint> findByRun(String project,String run) {
    return db.sql("""
        WITH valid AS (SELECT task,revision,CASE WHEN json_valid(snapshot) THEN snapshot END AS snapshot
          FROM checkpoint_revisions WHERE project=:project)
        SELECT task FROM valid WHERE json_extract(snapshot,'$.runId')=:run OR json_extract(snapshot,'$.originalRunId')=:run
        ORDER BY revision DESC LIMIT 1
        """).param("project",project).param("run",run).query(String.class).optional().map(task->get(project,task));
  }
  public PersistentCheckpoint save(PersistentCheckpoint state,long expected,String event) {
    var next=state.revision(expected+1);String encoded=encode(next);
    long incomingBytes=encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if(incomingBytes>settings.getMaxSnapshotBytes())throw CheckpointException.snapshotCapacity(incomingBytes,settings.getMaxSnapshotBytes());
    // Cross-DB order deliberately favors extra protection if this save fails.
    if(objects!=null)objects.protectCheckpoint(next);
    var removed=new ArrayList<HistoricalRow>();
    var saved=transaction.execute(tx->{
      db.sql("INSERT OR IGNORE INTO checkpoint_heads(project,task,revision) VALUES(?,?,0)").params(state.projectId(),state.taskId()).update();
      db.sql("UPDATE checkpoint_heads SET revision=revision WHERE project=? AND task=?").params(state.projectId(),state.taskId()).update();
      if(db.sql("SELECT COUNT(*) FROM checkpoint_event_keys WHERE project=? AND task=? AND event=?").params(state.projectId(),state.taskId(),event).query(Long.class).single()>0)return get(state.projectId(),state.taskId());
      if(db.sql("SELECT revision FROM checkpoint_heads WHERE project=? AND task=?").params(state.projectId(),state.taskId()).query(Long.class).single()!=expected)
        throw new ConcurrentModificationException("Checkpoint revision changed");
      var usage=historyUsage();var originalUsage=usage;
      Instant cutoff=Instant.now().minus(Duration.ofDays(settings.getRetentionDays()));
      // Ordinary tool boundaries need not decode every task or inspect approval snapshots.
      boolean expiredHistory=db.sql("SELECT EXISTS(SELECT 1 FROM checkpoint_revisions WHERE julianday(created)<julianday(?))")
          .param(cutoff.toString()).query(Integer.class).single()!=0;
      var history=exceeds(usage,incomingBytes)||expiredHistory?historyCandidates(next):null;
      for(var candidate:history==null?List.<HistoricalRow>of():history.rows()) {
        boolean expired;
        try {expired=Instant.parse(candidate.created()).isBefore(cutoff);}catch(RuntimeException invalid){expired=false;}
        if(!expired&&!exceeds(usage,incomingBytes))continue;
        remove(candidate);removed.add(candidate);
        usage=afterRemoval(usage,1,candidate.bytes());
      }
      // Throwing rolls back all tentative deletions, so report the actually retained usage.
      if(exceeds(usage,incomingBytes))throw CheckpointException.storageCapacity(originalUsage.revisions(),originalUsage.maxRevisions(),originalUsage.snapshotBytes(),incomingBytes,originalUsage.maxBytes(),history.protectedRevisions(),history.protectedBytes());
      if(db.sql("UPDATE checkpoint_heads SET revision=? WHERE project=? AND task=? AND revision=?").params(next.revision(),state.projectId(),state.taskId(),expected).update()!=1)
        throw new ConcurrentModificationException("Checkpoint revision changed");
      db.sql("INSERT INTO checkpoint_revisions(project,task,revision,event,snapshot,created) VALUES(?,?,?,?,?,?)").params(state.projectId(),state.taskId(),next.revision(),event,encoded,next.createdAt().toString()).update();
      db.sql("INSERT INTO checkpoint_event_keys(project,task,event) VALUES(?,?,?)").params(state.projectId(),state.taskId(),event).update();return next;
    });
    releaseReferences(removed);
    return saved;
  }
  public CheckpointHistory.Usage historyUsage() {
    return db.sql("SELECT COUNT(*) AS revisions,COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) AS bytes,(SELECT COUNT(*) FROM checkpoint_event_keys) AS keys FROM checkpoint_revisions")
        .query((row,index)->new CheckpointHistory.Usage(row.getLong("revisions"),row.getLong("keys"),row.getLong("bytes"),settings.getMaxRevisions(),settings.getMaxBytes())).single();
  }
  /** Preview and apply share the same eligibility rules; apply re-evaluates under SQLite's writer lock. */
  public CheckpointHistory.Cleanup cleanupHistory(boolean apply) {
    return cleanupHistory(apply,false);
  }
  public CheckpointHistory.Cleanup cleanupHistory(boolean apply,boolean force) {
    if(force)return clearHistory(apply);
    var removed=new ArrayList<HistoricalRow>();
    var result=transaction.execute(tx->{
      if(apply)db.sql("UPDATE checkpoint_heads SET revision=revision").update();
      var before=historyUsage();
      var history=historyCandidates(null);
      long bytes=0;
      for(var row:history.rows()){bytes+=row.bytes();if(apply){remove(row);removed.add(row);}}
      return new CheckpointHistory.Cleanup(history.rows().size(),bytes,history.protectedTasks(),before,
          afterRemoval(before,history.rows().size(),bytes),apply,history.protectedRevisions(),history.protectedBytes());
    });
    releaseReferences(removed);
    return result;
  }
  /** Explicit reset of checkpoint snapshots/heads; durable replay keys and approval receipts survive. */
  private CheckpointHistory.Cleanup clearHistory(boolean apply) {
    var removed=new ArrayList<HistoricalRow>();
    var result=transaction.execute(tx->{
      if(apply)db.sql("UPDATE checkpoint_heads SET revision=revision").update();
      var before=historyUsage();
      if(apply) {
        removed.addAll(db.sql("SELECT project,task,revision,length(CAST(snapshot AS BLOB)) AS bytes,created FROM checkpoint_revisions")
            .query((row,index)->new HistoricalRow(row.getString("project"),row.getString("task"),row.getLong("revision"),row.getLong("bytes"),row.getString("created"))).list());
        db.sql("DELETE FROM checkpoint_revisions").update();
        db.sql("DELETE FROM checkpoint_heads").update();
      }
      return new CheckpointHistory.Cleanup(before.revisions(),before.snapshotBytes(),0,before,
          new CheckpointHistory.Usage(0,before.eventKeys(),0,before.maxRevisions(),before.maxBytes()),apply,0,0,true);
    });
    releaseReferences(removed);
    return result;
  }
  public boolean recordedEvent(String project,String task,String event) {
    return db.sql("SELECT EXISTS(SELECT 1 FROM checkpoint_event_keys WHERE project=? AND task=? AND event=?)")
        .params(project,task,event).query(Integer.class).single()!=0;
  }
  private record TaskKey(String project,String task) {}
  private record RevisionKey(String project,String task,long revision) {}
  private record HistoricalRow(String project,String task,long revision,long bytes,String created) {}
  private record HistoryCandidates(List<HistoricalRow> rows,long protectedTasks,long protectedRevisions,long protectedBytes) {}
  private record ApprovalReference(String project,String task,String run,String session,String root) {}
  /** Called under the save/apply writer lock, or the preview's consistent read transaction. */
  private HistoryCandidates historyCandidates(PersistentCheckpoint incoming) {
    var protectedRevisions=new HashSet<RevisionKey>();
    var heads=new HashMap<TaskKey,Long>();
    // Snapshots are self-contained. A live lease or WAITING_APPROVAL never pins an entire task.
    db.sql("SELECT h.project,h.task,h.revision,r.snapshot FROM checkpoint_heads h LEFT JOIN checkpoint_revisions r ON r.project=h.project AND r.task=h.task AND r.revision=h.revision WHERE h.revision>0")
        .query((row,index)->{
          var key=new TaskKey(row.getString("project"),row.getString("task"));
          long revision=row.getLong("revision");
          heads.put(key,revision);
          try {
            var head=decode(new Row(revision,row.getString("snapshot")),key.project(),key.task());
            if(!replaces(incoming,key))protectState(protectedRevisions,head);
          }catch(RuntimeException damaged){
            // Keep the actual damaged head and the newest usable fallback, not all damaged history.
            protectedRevisions.add(new RevisionKey(key.project(),key.task(),revision));
            try(var history=rows(key.project(),key.task())) {
              var iterator=history.iterator();
              while(iterator.hasNext())try {protectState(protectedRevisions,decode(iterator.next(),key.project(),key.task()));
              break;}
                catch(RuntimeException invalid){/* Continue to the newest usable fallback. */}
            }
          }
          return key;
        }).list();
    if(incoming!=null)protectResumeSource(protectedRevisions,incoming);
    if(db.sql("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='approval_continuations'").query(Long.class).single()>0
        &&db.sql("SELECT EXISTS(SELECT 1 FROM approval_continuations WHERE state NOT IN ('COMPLETED','CANCELLED','DENIED'))").query(Integer.class).single()!=0) {
      // Old databases/tests may only contain a Run identity; modern receipts also scope its owner.
      boolean hasDocument=db.sql("SELECT COUNT(*) FROM pragma_table_info('approval_continuations') WHERE name='document'").query(Integer.class).single()>0;
      String ownerScope="""
          AND (json_extract(a.document,'$.owner.projectId') IS NULL
            OR (json_extract(a.document,'$.owner.projectId')=r.project
              AND (json_extract(a.document,'$.owner.conversationId') IS NULL
                OR json_extract(a.document,'$.owner.conversationId')=json_extract(r.snapshot,'$.sessionId'))))
          """;
      String approvalRows=hasDocument?"SELECT run,state,CASE WHEN json_valid(document) THEN document END AS document FROM approval_continuations"
          :"SELECT run,state,NULL AS document FROM approval_continuations";
      var references=db.sql("""
          WITH snapshots AS MATERIALIZED (
            SELECT project,task,CASE WHEN json_valid(snapshot) THEN snapshot END AS snapshot
            FROM checkpoint_revisions), approvals AS MATERIALIZED (
          """+approvalRows+"""
          )
          SELECT DISTINCT r.project,r.task,a.run,json_extract(a.document,'$.owner.conversationId') AS session,
            json_extract(a.document,'$.owner.projectRoot') AS root FROM snapshots r
          JOIN approvals a ON a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.runId')
            OR a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.originalRunId')
            OR a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.resumedFromRunId')
          WHERE a.state NOT IN ('COMPLETED','CANCELLED','DENIED')
          """+ownerScope).query((row,index)->new ApprovalReference(row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getString(5))).list();
      for(var reference:references) {
        // The new snapshot can replace this Run's old anchor in the very same transaction.
        if(replaces(incoming,new TaskKey(reference.project(),reference.task()))&&incoming.runId().equals(reference.run())&&approvalOwnerMatches(reference,incoming))continue;
        protectApprovalRun(protectedRevisions,reference);
      }
    }
    var rows=new ArrayList<HistoricalRow>();
    var protectedTasks=new HashSet<TaskKey>();
    long protectedCount=0,protectedBytes=0;
    var all=db.sql("SELECT project,task,revision,length(CAST(snapshot AS BLOB)) AS bytes,created FROM checkpoint_revisions")
        .query((row,index)->new HistoricalRow(row.getString("project"),row.getString("task"),row.getLong("revision"),row.getLong("bytes"),row.getString("created"))).list();
    for(var row:all) {
      var task=new TaskKey(row.project(),row.task());
      Long head=heads.get(task);
      // Orphans/future revisions cannot be classified safely and remain available for repair.
      if(head==null||row.revision()>head||protectedRevisions.contains(new RevisionKey(row.project(),row.task(),row.revision()))) {
        protectedTasks.add(task);
        protectedCount++;
        protectedBytes+=row.bytes();
      }else rows.add(row);
    }
    rows.sort(Comparator.comparing((HistoricalRow row)->historyTime(row.created())).thenComparing(HistoricalRow::project).thenComparing(HistoricalRow::task).thenComparingLong(HistoricalRow::revision));
    return new HistoryCandidates(List.copyOf(rows),protectedTasks.size(),protectedCount,protectedBytes);
  }
  private boolean replaces(PersistentCheckpoint incoming,TaskKey task) {
    // Unknown schemas must not evict the last readable state. Deletions and insert commit together.
    return incoming!=null&&incoming.schemaVersion()==1&&incoming.projectId().equals(task.project())&&incoming.taskId().equals(task.task());
  }
  private void protectState(Set<RevisionKey> protectedRevisions,PersistentCheckpoint state) {
    protectedRevisions.add(new RevisionKey(state.projectId(),state.taskId(),state.revision()));
    protectResumeSource(protectedRevisions,state);
  }
  private void protectResumeSource(Set<RevisionKey> protectedRevisions,PersistentCheckpoint state) {
    // This is lineage, not an incremental snapshot chain: do not recursively pin all ancestors.
    if(state.resumedFromRevision()!=null&&state.resumedFromRevision()>0)
      protectedRevisions.add(new RevisionKey(state.projectId(),state.taskId(),state.resumedFromRevision()));
  }
  private void protectApprovalRun(Set<RevisionKey> protectedRevisions,ApprovalReference reference) {
    for(String field:List.of("runId","originalRunId","resumedFromRunId")) {
      var matches=db.sql("SELECT revision,snapshot FROM checkpoint_revisions WHERE project=? AND task=? AND json_extract(CASE WHEN json_valid(snapshot) THEN snapshot END,'$."+field+"')=? ORDER BY revision DESC")
          .params(reference.project(),reference.task(),reference.run()).query((row,index)->new Row(row.getLong(1),row.getString(2)));
      try(var history=matches.stream()) {
        var iterator=history.iterator();
        while(iterator.hasNext())try {
            var state=decode(iterator.next(),reference.project(),reference.task());
            if(approvalOwnerMatches(reference,state)){protectState(protectedRevisions,state);
            return;}
          }
          catch(RuntimeException invalid){/* Preserve the newest valid anchor, never a whole Run's history. */}
      }
    }
  }
  private boolean approvalOwnerMatches(ApprovalReference reference,PersistentCheckpoint state) {
    if(reference.session()!=null&&!reference.session().equals(state.sessionId()))return false;
    if(reference.root()==null)return true;
    try {
      // Jackson stores a Path as a file URI; checkpoint.projectRoot is the native path string.
      var root=reference.root().startsWith("file:")?java.nio.file.Path.of(java.net.URI.create(reference.root())):java.nio.file.Path.of(reference.root());
      return root.toAbsolutePath().normalize().equals(java.nio.file.Path.of(state.projectRoot()).toAbsolutePath().normalize());
    }catch(RuntimeException invalid){return true;} // A damaged receipt must not cause evidence deletion.
  }
  private Instant historyTime(String created) {
    try{return Instant.parse(created);}catch(RuntimeException invalid){return Instant.MAX;}
  }
  private boolean exceeds(CheckpointHistory.Usage usage,long incomingBytes) {
    return usage.revisions()>=usage.maxRevisions()||incomingBytes>usage.maxBytes()-usage.snapshotBytes();
  }
  private CheckpointHistory.Usage afterRemoval(CheckpointHistory.Usage usage,long count,long bytes) {
    return new CheckpointHistory.Usage(usage.revisions()-count,usage.eventKeys(),usage.snapshotBytes()-bytes,usage.maxRevisions(),usage.maxBytes());
  }
  private void remove(HistoricalRow row) {
    db.sql("DELETE FROM checkpoint_revisions WHERE project=? AND task=? AND revision=?").params(row.project(),row.task(),row.revision()).update();
    // Keep deduplication keys: a late event must never resurrect a removed revision or side effect.
  }
  private void releaseReferences(List<HistoricalRow> removed) {
    if(objects!=null&&!removed.isEmpty())try {
      objects.releaseCheckpointHistory(removed.stream().map(row->row.project()+":"+row.task()+":"+row.revision()).toList());
    }catch(RuntimeException unavailable){/* Cross-DB failure leaves extra protection, never unprotected live evidence. */}
  }
  public boolean acquire(String project,String task,String run) {
    return Boolean.TRUE.equals(transaction.execute(tx->{
      db.sql("UPDATE checkpoint_heads SET revision=revision WHERE project=? AND task=?").params(project,task).update();
      var owner=db.sql("SELECT owner FROM checkpoint_heads WHERE project=? AND task=?").params(project,task).query(String.class).optional();
      if(owner.isPresent()&&alive(owner.get()))return false;
      return db.sql("UPDATE checkpoint_heads SET owner=?,lease_run=? WHERE project=? AND task=?").params(OWNER,run,project,task).update()==1;
    }));
  }
  public boolean leased(String project,String task) {
    return db.sql("SELECT owner FROM checkpoint_heads WHERE project=? AND task=?").params(project,task).query(String.class).optional().map(PersistentCheckpointRepository::alive).orElse(false);
  }
  public void release(String project,String task,String run) {
    db.sql("UPDATE checkpoint_heads SET owner=NULL,lease_run=NULL WHERE project=? AND task=? AND owner=? AND lease_run=?").params(project,task,OWNER,run).update();
  }
  static boolean alive(String owner) {
    try {var parts=owner.split("@",2);return ProcessHandle.of(Long.parseLong(parts[0])).filter(ProcessHandle::isAlive)
        .flatMap(p->p.info().startInstant()).map(i->i.toString().equals(parts[1])).orElse(false);}
    catch(RuntimeException e){return false;}
  }
  public Map<String,Object> fields(PersistentCheckpoint state){return json.convertValue(state,new com.fasterxml.jackson.core.type.TypeReference<>(){});}
  public PersistentCheckpoint fields(Map<String,Object> fields){return json.convertValue(fields,PersistentCheckpoint.class);}
  private record Row(long revision,String snapshot){}
  private java.util.stream.Stream<Row> rows(String project,String task) {
    return db.sql("SELECT revision,snapshot FROM checkpoint_revisions WHERE project=? AND task=? ORDER BY revision DESC").params(project,task).query((row,index)->new Row(row.getLong(1),row.getString(2))).stream();
  }
  private PersistentCheckpoint decode(Row row,String project,String task) {
    try {var state=json.readValue(row.snapshot(),PersistentCheckpoint.class);
      if(state.schemaVersion()!=1)throw new CheckpointException(CheckpointException.Code.INCOMPATIBLE_SCHEMA,"Unsupported checkpoint schema: "+state.schemaVersion());
      if(state.revision()!=row.revision()||!state.projectId().equals(project)||!state.taskId().equals(task))throw new CheckpointException(CheckpointException.Code.CORRUPT_CHECKPOINT,"Checkpoint identity/revision mismatch");
      return state;
    }catch(java.io.IOException e){throw new CheckpointException(CheckpointException.Code.CORRUPT_CHECKPOINT,"Corrupt checkpoint");}
  }
  public String encode(PersistentCheckpoint state) {
    try{return json.writeValueAsString(state);}catch(java.io.IOException e){throw new IllegalStateException("Cannot encode checkpoint",e);}
  }
}
