package dev.mikoto2000.rei.core.dependency;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;

/** Immutable conditions and an acyclic graph: prerequisites can only refer to existing entries. */
@Component
public class PersistentDependencyRepository {
  public record Entry(String id,String projectId,String projectRoot,String sessionId,DependencySpec spec,
      Instant createdAt,Instant deadline,DependencyState state,String reason,long version,String answer,List<String> prerequisites) {
    public Entry {prerequisites=List.copyOf(prerequisites);}
  }
  public record Fact(String eventId,String dependencyId,String projectId,String sessionId,String kind,DependencyState state,String reason,long version,Instant timestamp) {}
  private final JdbcClient db;private final TransactionTemplate transaction;private final Clock clock;
  public PersistentDependencyRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS agent_dependencies(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,target TEXT NOT NULL,expected TEXT,created INTEGER NOT NULL,deadline INTEGER NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,version INTEGER NOT NULL,answer TEXT)").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_dependencies_active ON agent_dependencies(state,id)").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_dependency_edges(child TEXT NOT NULL,parent TEXT NOT NULL,ordinal INTEGER NOT NULL,PRIMARY KEY(child,parent))").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_dependency_facts(sequence INTEGER PRIMARY KEY AUTOINCREMENT,event TEXT NOT NULL UNIQUE,dependency TEXT NOT NULL,project TEXT NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,state TEXT NOT NULL,reason TEXT NOT NULL,version INTEGER NOT NULL,timestamp INTEGER NOT NULL,sent INTEGER NOT NULL DEFAULT 0,UNIQUE(dependency,version))").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_dependency_pending_facts ON agent_dependency_facts(sent,sequence)").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_dependency_fact_history ON agent_dependency_facts(dependency,sequence)").update();
  }
  private final RowMapper<Entry> row=(rs,n)->new Entry(rs.getString("id"),rs.getString("project"),rs.getString("root"),rs.getString("session"),
      new DependencySpec(DependencySpec.Kind.valueOf(rs.getString("kind")),rs.getString("target"),rs.getString("expected")),
      Instant.ofEpochMilli(rs.getLong("created")),Instant.ofEpochMilli(rs.getLong("deadline")),DependencyState.valueOf(rs.getString("state")),rs.getString("reason"),rs.getLong("version"),rs.getString("answer"),parents(rs.getString("id")));
  private List<String> parents(String id){return db.sql("SELECT parent FROM agent_dependency_edges WHERE child=? ORDER BY ordinal").param(id).query(String.class).list();}
  public Entry create(AgentRunContext owner,DependencySpec spec,Duration lifetime,List<String> prerequisites) {
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||owner.conversationId().isBlank())throw new IllegalArgumentException("Owning Project/Session required");
    if(spec==null||lifetime==null||lifetime.compareTo(Duration.ofSeconds(1))<0||lifetime.compareTo(Duration.ofDays(366))>0)throw new IllegalArgumentException("Lifetime must be 1 second..366 days");
    var deps=List.copyOf(prerequisites==null?List.of():prerequisites);
    if(deps.size()>16||new HashSet<>(deps).size()!=deps.size())throw new IllegalArgumentException("At most 16 unique prerequisites");
    return transaction.execute(status->{
      for(String id:deps){var parent=get(owner.projectId(),id);if(!parent.sessionId().equals(owner.conversationId())||!parent.projectRoot().equals(owner.projectRoot().toString()))throw new IllegalArgumentException("Prerequisites must share Project/root/Session");}
      String id="dep-"+UUID.randomUUID();Instant now=clock.instant();String state=deps.isEmpty()?"WAITING":"BLOCKED";String reason=deps.isEmpty()?"watch_pending":"dependency_waiting";
      int inserted=db.sql("INSERT INTO agent_dependencies(id,project,root,session,kind,target,expected,created,deadline,state,reason,version) SELECT ?,?,?,?,?,?,?,?,?,?,?,0 WHERE (SELECT COUNT(*) FROM agent_dependencies WHERE project=? AND state IN ('RUNNING','WAITING','BLOCKED'))<256")
          .params(id,owner.projectId(),owner.projectRoot().toString(),owner.conversationId(),spec.kind().name(),spec.target(),spec.expected(),now.toEpochMilli(),now.plus(lifetime).toEpochMilli(),state,reason,owner.projectId()).update();
      if(inserted!=1)throw new IllegalStateException("Project active dependency limit reached (256)");
      for(int i=0;i<deps.size();i++)db.sql("INSERT INTO agent_dependency_edges(child,parent,ordinal) VALUES(?,?,?)").params(id,deps.get(i),i).update();
      var entry=get(owner.projectId(),id);fact(entry);return entry;
    });
  }
  public Entry get(String project,String id){return db.sql("SELECT * FROM agent_dependencies WHERE project=? AND id=?").params(project,id).query(row).optional().orElseThrow(()->new IllegalArgumentException("Dependency not found in this Project"));}
  public List<Entry> list(String project){return db.sql("SELECT * FROM agent_dependencies WHERE project=? ORDER BY created DESC,id LIMIT 256").param(project).query(row).list();}
  public List<Entry> activeAfter(String after){return db.sql("SELECT * FROM agent_dependencies WHERE state IN ('RUNNING','WAITING','BLOCKED') AND id>? ORDER BY id LIMIT 8").param(after).query(row).list();}
  public static boolean terminal(DependencyState state){return state==DependencyState.COMPLETED||state==DependencyState.FAILED||state==DependencyState.CANCELLED;}
  public Entry prepare(String project,String id) {
    var entry=get(project,id);if(terminal(entry.state()))return entry;
    if(!entry.deadline().isAfter(clock.instant()))return observe(entry,DependencyState.FAILED,"dependency_deadline_expired");
    boolean waiting=false;
    for(String parentId:entry.prerequisites()) {
      var parent=get(project,parentId);
      if(parent.state()!=DependencyState.COMPLETED){if(terminal(parent.state()))return observe(entry,DependencyState.BLOCKED,"dependency_failed");waiting=true;}
    }
    if(waiting)return observe(entry,DependencyState.BLOCKED,"dependency_waiting");
    if(entry.reason().startsWith("dependency_"))return observe(entry,DependencyState.WAITING,"watch_pending");
    return entry;
  }
  public Entry observe(Entry expected,DependencyState state,String reason) {
    if(state==null||reason==null||!reason.matches("[a-z_]{1,80}"))throw new IllegalArgumentException("Structured observation required");
    return transaction.execute(status->{
      if(expected.state()==state&&expected.reason().equals(reason))return get(expected.projectId(),expected.id());
      int changed=db.sql("UPDATE agent_dependencies SET state=?,reason=?,version=version+1 WHERE project=? AND id=? AND version=? AND state IN ('RUNNING','WAITING','BLOCKED')")
          .params(state.name(),reason,expected.projectId(),expected.id(),expected.version()).update();
      var current=get(expected.projectId(),expected.id());if(changed==1)fact(current);return current;
    });
  }
  public Entry cancel(String project,String id){var entry=get(project,id);if(terminal(entry.state()))throw new IllegalStateException("Dependency is terminal");return observe(entry,DependencyState.CANCELLED,"user_cancelled");}
  public Entry answer(String project,String id,String text) {
    var entry=prepare(project,id);
    if(entry.spec().kind()!=DependencySpec.Kind.USER_ANSWER||text==null||text.isBlank()||text.length()>4096)throw new IllegalArgumentException("User-answer dependency and 1..4096 character answer required");
    return transaction.execute(status->{
      if(db.sql("UPDATE agent_dependencies SET answer=?,version=version+1 WHERE project=? AND id=? AND version=? AND state IN ('RUNNING','WAITING','BLOCKED')")
          .params(CredentialRedactor.redact(text),project,id,entry.version()).update()!=1)throw new IllegalStateException("Dependency is terminal or changed");
      var current=get(project,id);fact(current);return current;
    });
  }
  private void fact(Entry entry){db.sql("INSERT INTO agent_dependency_facts(event,dependency,project,session,kind,state,reason,version,timestamp) VALUES(?,?,?,?,?,?,?,?,?)")
      .params(UUID.randomUUID().toString(),entry.id(),entry.projectId(),entry.sessionId(),entry.spec().kind().name(),entry.state().name(),entry.reason(),entry.version(),clock.millis()).update();}
  public List<Fact> pendingFacts(){return db.sql("SELECT * FROM agent_dependency_facts WHERE sent=0 ORDER BY sequence LIMIT 16").query((rs,n)->new Fact(rs.getString("event"),rs.getString("dependency"),rs.getString("project"),rs.getString("session"),rs.getString("kind"),DependencyState.valueOf(rs.getString("state")),rs.getString("reason"),rs.getLong("version"),Instant.ofEpochMilli(rs.getLong("timestamp")))).list();}
  public void ackFact(String event){db.sql("UPDATE agent_dependency_facts SET sent=1 WHERE event=?").param(event).update();}
  public List<Fact> history(String project,String id){get(project,id);return db.sql("SELECT * FROM agent_dependency_facts WHERE dependency=? ORDER BY sequence DESC LIMIT 256").param(id).query((rs,n)->new Fact(rs.getString("event"),id,project,rs.getString("session"),rs.getString("kind"),DependencyState.valueOf(rs.getString("state")),rs.getString("reason"),rs.getLong("version"),Instant.ofEpochMilli(rs.getLong("timestamp")))).list();}
}
