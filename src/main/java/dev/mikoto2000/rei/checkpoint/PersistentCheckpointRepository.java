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
    if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>settings.getMaxSnapshotBytes())throw new CheckpointException(CheckpointException.Code.CAPACITY,"Checkpoint snapshot capacity reached");
    // Cross-DB order deliberately favors extra protection if this save fails.
    if(objects!=null)objects.protectCheckpoint(next);
    return transaction.execute(tx->{
      db.sql("INSERT OR IGNORE INTO checkpoint_heads(project,task,revision) VALUES(?,?,0)").params(state.projectId(),state.taskId()).update();
      db.sql("UPDATE checkpoint_heads SET revision=revision WHERE project=? AND task=?").params(state.projectId(),state.taskId()).update();
      if(db.sql("SELECT COUNT(*) FROM checkpoint_event_keys WHERE project=? AND task=? AND event=?").params(state.projectId(),state.taskId(),event).query(Long.class).single()>0)return get(state.projectId(),state.taskId());
      prune();
      if(db.sql("SELECT COUNT(*) FROM checkpoint_event_keys").query(Long.class).single()>=settings.getMaxRevisions()
          ||db.sql("SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM checkpoint_revisions").query(Long.class).single()+encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>settings.getMaxBytes())
        throw new CheckpointException(CheckpointException.Code.CAPACITY,"Checkpoint storage capacity reached; previous revisions retained");
      if(db.sql("UPDATE checkpoint_heads SET revision=? WHERE project=? AND task=? AND revision=?").params(next.revision(),state.projectId(),state.taskId(),expected).update()!=1)
        throw new ConcurrentModificationException("Checkpoint revision changed");
      db.sql("INSERT INTO checkpoint_revisions(project,task,revision,event,snapshot,created) VALUES(?,?,?,?,?,?)").params(state.projectId(),state.taskId(),next.revision(),event,encoded,next.createdAt().toString()).update();
      db.sql("INSERT INTO checkpoint_event_keys(project,task,event) VALUES(?,?,?)").params(state.projectId(),state.taskId(),event).update();return next;
    });
  }
  private void prune() {
    String cutoff=Instant.now().minus(Duration.ofDays(settings.getRetentionDays())).toString();
    for(var candidate:db.sql("SELECT DISTINCT project,task FROM checkpoint_revisions WHERE created<?").param(cutoff).query((row,index)->new String[]{row.getString(1),row.getString(2)}).list()) {
      try {long valid=get(candidate[0],candidate[1]).revision();
        db.sql("DELETE FROM checkpoint_revisions WHERE project=? AND task=? AND created<? AND revision<?").params(candidate[0],candidate[1],cutoff,valid).update();
      }catch(IllegalArgumentException|IllegalStateException corrupt){/* Retain damaged history for repair. */}
    }
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
