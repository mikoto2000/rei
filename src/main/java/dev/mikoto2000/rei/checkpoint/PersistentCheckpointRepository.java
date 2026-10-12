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
      var candidates=exceeds(usage,incomingBytes)||expiredHistory?historyCandidates().rows():List.<HistoricalRow>of();
      for(var candidate:candidates) {
        boolean expired;
        try {expired=Instant.parse(candidate.created()).isBefore(cutoff);}catch(RuntimeException invalid){expired=false;}
        if(!expired&&!exceeds(usage,incomingBytes))continue;
        remove(candidate);removed.add(candidate);
        usage=afterRemoval(usage,1,candidate.bytes());
      }
      // Throwing rolls back all tentative deletions, so report the actually retained usage.
      if(exceeds(usage,incomingBytes))throw CheckpointException.storageCapacity(originalUsage.revisions(),originalUsage.maxRevisions(),originalUsage.snapshotBytes(),incomingBytes,originalUsage.maxBytes());
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
    var removed=new ArrayList<HistoricalRow>();
    var result=transaction.execute(tx->{
      if(apply)db.sql("UPDATE checkpoint_heads SET revision=revision").update();
      var before=historyUsage();var history=historyCandidates();long bytes=0;
      for(var row:history.rows()){bytes+=row.bytes();if(apply){remove(row);removed.add(row);}}
      return new CheckpointHistory.Cleanup(history.rows().size(),bytes,history.protectedTasks(),before,
          afterRemoval(before,history.rows().size(),bytes),apply);
    });
    releaseReferences(removed);
    return result;
  }
  private record TaskKey(String project,String task) {}
  private record HistoricalRow(String project,String task,long revision,long bytes,String created) {}
  private record HistoryCandidates(List<HistoricalRow> rows,long protectedTasks) {}
  private HistoryCandidates historyCandidates() {
    var protectedTasks=new HashSet<TaskKey>();
    // Inspect the actual head, rather than get()'s valid fallback, to preserve damaged history for repair.
    db.sql("SELECT h.project,h.task,h.revision,h.owner,r.snapshot FROM checkpoint_heads h LEFT JOIN checkpoint_revisions r ON r.project=h.project AND r.task=h.task AND r.revision=h.revision WHERE h.revision>0")
        .query((row,index)->{
          var key=new TaskKey(row.getString("project"),row.getString("task"));
          try {var head=decode(new Row(row.getLong("revision"),row.getString("snapshot")),key.project(),key.task());
            String owner=row.getString("owner");
            if((owner!=null&&alive(owner))||"WAITING_APPROVAL".equals(head.status()))protectedTasks.add(key);
          }catch(RuntimeException damaged){protectedTasks.add(key);}
          return key;
        }).list();
    if(db.sql("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='approval_continuations'").query(Long.class).single()>0
        &&db.sql("SELECT EXISTS(SELECT 1 FROM approval_continuations WHERE state NOT IN ('COMPLETED','CANCELLED','DENIED'))").query(Integer.class).single()!=0) {
      protectedTasks.addAll(db.sql("""
          SELECT DISTINCT r.project,r.task FROM checkpoint_revisions r
          JOIN approval_continuations a ON a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.runId')
            OR a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.originalRunId')
            OR a.run=json_extract(CASE WHEN json_valid(r.snapshot) THEN r.snapshot END,'$.resumedFromRunId')
          WHERE a.state NOT IN ('COMPLETED','CANCELLED','DENIED')
          """).query((row,index)->new TaskKey(row.getString(1),row.getString(2))).list());
    }
    var rows=db.sql("""
        SELECT r.project,r.task,r.revision,length(CAST(r.snapshot AS BLOB)) AS bytes,r.created
        FROM checkpoint_revisions r JOIN checkpoint_heads h ON h.project=r.project AND h.task=r.task
        WHERE r.revision<h.revision
        """).query((row,index)->new HistoricalRow(row.getString("project"),row.getString("task"),row.getLong("revision"),row.getLong("bytes"),row.getString("created"))).list()
        .stream().filter(row->!protectedTasks.contains(new TaskKey(row.project(),row.task())))
        .sorted(Comparator.comparing((HistoricalRow row)->historyTime(row.created())).thenComparing(HistoricalRow::project).thenComparing(HistoricalRow::task).thenComparingLong(HistoricalRow::revision)).toList();
    return new HistoryCandidates(rows,protectedTasks.size());
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
