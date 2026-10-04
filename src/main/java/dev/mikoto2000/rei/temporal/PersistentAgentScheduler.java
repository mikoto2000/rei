package dev.mikoto2000.rei.temporal;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;

/** Persistent bounded continuations. A claimed action is never automatically retried after uncertain execution. */
@Component
public class PersistentAgentScheduler implements AgentScheduler {
  public record Entry(ScheduledAgentTask task,String projectId,String projectRoot,String status,String runId,String outcome) {}
  public record History(String status,Instant timestamp,String detail) {}
  private final JdbcClient db;
  private final Clock clock;
  private final TransactionTemplate transaction;
  public PersistentAgentScheduler(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedules(id TEXT PRIMARY KEY,created INTEGER NOT NULL,due INTEGER NOT NULL,action TEXT NOT NULL,session TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,status TEXT NOT NULL,run TEXT,outcome TEXT NOT NULL DEFAULT '')").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_schedules_due ON agent_schedules(status,due)").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS agent_schedules_session_running ON agent_schedules(project,session) WHERE status='RUNNING'").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_events(id TEXT PRIMARY KEY,source_run TEXT NOT NULL,type TEXT NOT NULL,expires INTEGER NOT NULL,activated INTEGER,matched_event TEXT)").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_schedule_events_source ON agent_schedule_events(source_run,type)").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_event_cursors(project TEXT PRIMARY KEY,offset INTEGER NOT NULL,discard INTEGER NOT NULL,generation INTEGER NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_crons(id TEXT PRIMARY KEY,expression TEXT NOT NULL,zone TEXT NOT NULL,remaining INTEGER NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_intervals(id TEXT PRIMARY KEY,interval_ms INTEGER NOT NULL,remaining INTEGER NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS agent_schedule_history(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL,status TEXT NOT NULL,timestamp INTEGER NOT NULL,detail TEXT NOT NULL)").update();
  }
  private static final RowMapper<Entry> ROW=(rs,n)->new Entry(new ScheduledAgentTask(rs.getString("id"),Instant.ofEpochMilli(rs.getLong("created")),
      Instant.ofEpochMilli(rs.getLong("due")),rs.getString("action"),rs.getString("session")),rs.getString("project"),rs.getString("root"),rs.getString("status"),rs.getString("run"),rs.getString("outcome"));
  @Override public ScheduledAgentTask scheduleAfter(Duration duration,String action,String conversationId) {
    if(duration==null||duration.isNegative()||duration.compareTo(Duration.ofDays(366))>0)throw new IllegalArgumentException("Duration must be 0..366 days");
    return scheduleAt(clock.instant().plus(duration),action,conversationId);
  }
  @Override public ScheduledAgentTask scheduleAt(Instant executeAt,String action,String conversationId) {
    var owner=AgentRunScope.current();
    if(owner==null||owner.projectId()==null||owner.projectId().isBlank()||!owner.conversationId().equals(conversationId))
      throw new IllegalArgumentException("Schedule requires the current owning project and exact conversationId");
    if(action==null||action.isBlank()||action.length()>4096)throw new IllegalArgumentException("Action must contain 1..4096 characters");
    if(executeAt==null||executeAt.isBefore(clock.instant())||executeAt.isAfter(clock.instant().plus(Duration.ofDays(366))))
      throw new IllegalArgumentException("Execute time must be now..366 days in the future");
    var task=new ScheduledAgentTask("timer-"+UUID.randomUUID(),clock.instant(),executeAt,action,conversationId);
    transaction.executeWithoutResult(status->{
      int count=db.sql("INSERT INTO agent_schedules(id,created,due,action,session,project,root,status) SELECT ?,?,?,?,?,?,?,'PENDING' WHERE (SELECT COUNT(*) FROM agent_schedules WHERE project=? AND status IN ('PENDING','SCHEDULED','RUNNING','WAITING_EVENT'))<256")
          .params(task.id(),task.createdAt().toEpochMilli(),executeAt.toEpochMilli(),action,conversationId,owner.projectId(),owner.projectRoot().toString(),owner.projectId()).update();
      if(count!=1)throw new IllegalStateException("Project schedule limit reached (256)");
      history(task.id(),"PENDING","");
    });return task;
  }
  public record Interval(Duration interval,int remaining) {}
  public Optional<Interval> interval(String project,String id) {
    get(project,id);
    return db.sql("SELECT interval_ms,remaining FROM agent_schedule_intervals WHERE id=?").param(id)
        .query((rs,n)->new Interval(Duration.ofMillis(rs.getLong(1)),rs.getInt(2))).optional();
  }
  /** Bounded repeat; successful occurrences coalesce missed time from completion. */
  public ScheduledAgentTask scheduleInterval(Duration interval,int occurrences,String action,String conversationId) {
    if(interval==null||interval.compareTo(Duration.ofMinutes(1))<0||interval.compareTo(Duration.ofDays(366))>0)
      throw new IllegalArgumentException("Interval must be 1 minute..366 days");
    if(occurrences<2||occurrences>100)throw new IllegalArgumentException("Occurrences must be 2..100");
    return transaction.execute(status->{
      var task=scheduleAfter(interval,action,conversationId);
      db.sql("INSERT INTO agent_schedule_intervals(id,interval_ms,remaining) VALUES(?,?,?)")
          .params(task.id(),interval.toMillis(),occurrences).update();return task;
    });
  }
  public record Cron(String expression,String zone,int remaining) {}
  public Optional<Cron> cron(String project,String id) {
    get(project,id);
    return db.sql("SELECT expression,zone,remaining FROM agent_schedule_crons WHERE id=?").param(id)
        .query((rs,n)->new Cron(rs.getString(1),rs.getString(2),rs.getInt(3))).optional();
  }
  /** Six-field cron at minute granularity; wall clock evaluated in the explicit zone. */
  public ScheduledAgentTask scheduleCron(String expression,String zone,int occurrences,String action,String conversationId) {
    if(expression==null||expression.length()>256||!expression.trim().matches("0\\s+.*"))
      throw new IllegalArgumentException("Cron must have six fields and seconds fixed to 0");
    if(zone==null||zone.length()>128)throw new IllegalArgumentException("Explicit time zone is required");
    if(occurrences<2||occurrences>100)throw new IllegalArgumentException("Occurrences must be 2..100");
    final Instant due;
    try {due=nextCron(expression,zone,clock.instant());}
    catch(java.time.DateTimeException error){throw new IllegalArgumentException("Invalid cron time zone",error);}
    if(due==null)throw new IllegalArgumentException("Cron has no future occurrence");
    return transaction.execute(status->{
      var task=scheduleAt(due,action,conversationId);
      db.sql("INSERT INTO agent_schedule_crons(id,expression,zone,remaining) VALUES(?,?,?,?)")
          .params(task.id(),expression.trim(),zone,occurrences).update();return task;
    });
  }
  private static Instant nextCron(String expression,String zone,Instant after) {
    var next=org.springframework.scheduling.support.CronExpression.parse(expression).next(after.atZone(ZoneId.of(zone)));
    return next==null?null:next.toInstant();
  }
  private void scheduleNext(String id,Instant due,String reason) {
    db.sql("UPDATE agent_schedules SET status='SCHEDULED',due=?,run=NULL WHERE id=?")
        .params(due.toEpochMilli(),id).update();
    history(id,"SCHEDULED",reason);
  }
  private static final Set<AgentEventType> TERMINAL_EVENTS=Set.of(
      AgentEventType.AGENT_RUN_COMPLETED,AgentEventType.AGENT_RUN_FAILED,AgentEventType.AGENT_RUN_CANCELLED,
      AgentEventType.EXECUTION_COMPLETED,AgentEventType.EXECUTION_FAILED,AgentEventType.EXECUTION_CANCELLED);
  public record EventTrigger(String sourceRunId,AgentEventType type,Instant expiresAt,Long activatedAt,String matchedEventId) {}
  public Optional<EventTrigger> eventTrigger(String project,String id) {
    get(project,id);
    return db.sql("SELECT * FROM agent_schedule_events WHERE id=?").param(id).query((rs,n)->{
      long activated=rs.getLong("activated");boolean absent=rs.wasNull();
      return new EventTrigger(rs.getString("source_run"),AgentEventType.valueOf(rs.getString("type")),
          Instant.ofEpochMilli(rs.getLong("expires")),absent?null:activated,rs.getString("matched_event"));
    }).optional();
  }
  public ScheduledAgentTask scheduleOnEvent(String sourceRun,AgentEventType type,Duration expiresAfter,String action,String conversationId) {
    if(sourceRun==null||sourceRun.isBlank()||sourceRun.length()>128||type==null||!TERMINAL_EVENTS.contains(type))
      throw new IllegalArgumentException("An exact source Run ID and supported terminal event are required");
    if(expiresAfter==null||expiresAfter.compareTo(Duration.ofSeconds(1))<0||expiresAfter.compareTo(Duration.ofDays(366))>0)
      throw new IllegalArgumentException("Event wait expiry must be 1 second..366 days");
    return transaction.execute(status->{
      var task=scheduleAfter(expiresAfter,action,conversationId);
      db.sql("INSERT INTO agent_schedule_events(id,source_run,type,expires) VALUES(?,?,?,?)")
          .params(task.id(),sourceRun,type.name(),task.executeAt().toEpochMilli()).update();return task;
    });
  }
  /** Event facts make an activated wait due; no model or external action executes here. */
  public void signalEvent(AgentEvent event) {
    if(event==null||!TERMINAL_EVENTS.contains(event.type())||event.projectId()==null||event.sessionId()==null||event.runId()==null
        ||event.id().length()>128||event.timestamp().isAfter(clock.instant()))return;
    transaction.executeWithoutResult(status->{
      var entries=db.sql("UPDATE agent_schedules SET status='SCHEDULED',due=? WHERE project=? AND session=? AND status='WAITING_EVENT' AND id IN (SELECT id FROM agent_schedule_events WHERE source_run=? AND type=? AND activated<=? AND expires>? AND expires>=?) RETURNING *")
          .params(clock.millis(),event.projectId(),event.sessionId(),event.runId(),event.type().name(),event.timestamp().toEpochMilli(),clock.millis(),event.timestamp().toEpochMilli())
          .query(ROW).list();
      for(var entry:entries) {
        db.sql("UPDATE agent_schedule_events SET matched_event=? WHERE id=?").params(event.id(),entry.task().id()).update();
        history(entry.task().id(),"SCHEDULED","event:"+event.id());
      }
    });
  }
  public void expireEventWaits() {
    transaction.executeWithoutResult(status->{
      var expired=db.sql("UPDATE agent_schedules SET status='FAILED',outcome='event_wait_expired' WHERE id IN (SELECT id FROM agent_schedules WHERE status='WAITING_EVENT' AND due<=? ORDER BY due,id LIMIT 256) RETURNING *")
          .param(clock.millis()).query(ROW).list();
      expired.forEach(entry->history(entry.task().id(),"FAILED","event_wait_expired"));
    });
  }
  public record ReplayCursor(long offset,boolean discardingLine,long generation) {}
  ReplayCursor replayCursor(String project) {
    return db.sql("SELECT * FROM agent_schedule_event_cursors WHERE project=?").param(project)
        .query((rs,n)->new ReplayCursor(rs.getLong("offset"),rs.getInt("discard")!=0,rs.getLong("generation"))).single();
  }
  void saveReplayCursor(String project,ReplayCursor expected,long offset,boolean discard) {
    db.sql("UPDATE agent_schedule_event_cursors SET offset=?,discard=? WHERE project=? AND offset=? AND generation=?")
        .params(offset,discard?1:0,project,expected.offset(),expected.generation()).update();
  }
  List<String> waitingEventProjects(String after) {
    return db.sql("SELECT DISTINCT project FROM agent_schedules WHERE status='WAITING_EVENT' AND project>? ORDER BY project LIMIT 8")
        .param(after).query(String.class).list();
  }
  @Override public List<ScheduledAgentTask> list() {
    var owner=AgentRunScope.current();if(owner==null||owner.projectId()==null)throw new IllegalArgumentException("Owning project is required");
    return db.sql("SELECT * FROM agent_schedules WHERE project=? AND session=? AND status IN ('PENDING','SCHEDULED','RUNNING','WAITING_EVENT') ORDER BY due,id LIMIT 256")
        .params(owner.projectId(),owner.conversationId()).query(ROW).list().stream().map(Entry::task).toList();
  }
  public List<Entry> list(String project) {return db.sql("SELECT * FROM agent_schedules WHERE project=? ORDER BY created DESC,id LIMIT 256").param(project).query(ROW).list();}
  public Entry get(String project,String id) {return db.sql("SELECT * FROM agent_schedules WHERE project=? AND id=?").params(project,id).query(ROW).optional()
      .orElseThrow(()->new IllegalArgumentException("Schedule not found in this project"));}
  /** Human-facing control only; no Tool exposes activation. */
  public void activate(String project,String id) {
    transaction.executeWithoutResult(status->{
      var trigger=eventTrigger(project,id);
      if(trigger.isEmpty()) {transition(project,id,"PENDING","SCHEDULED");return;}
      if(!trigger.get().expiresAt().isAfter(clock.instant()))throw new IllegalStateException("Event wait already expired");
      transition(project,id,"PENDING","WAITING_EVENT");
      db.sql("UPDATE agent_schedule_events SET activated=? WHERE id=?").params(clock.millis(),id).update();
      db.sql("INSERT INTO agent_schedule_event_cursors(project,offset,discard,generation) VALUES(?,0,0,0) ON CONFLICT(project) DO UPDATE SET offset=0,discard=0,generation=generation+1")
          .param(project).update();
    });
  }
  public void cancel(String project,String id) {
    transaction.executeWithoutResult(status->{get(project,id);
      if(db.sql("UPDATE agent_schedules SET status='CANCELLED' WHERE project=? AND id=? AND status IN ('PENDING','SCHEDULED','WAITING_EVENT')").params(project,id).update()!=1)
        throw new IllegalStateException("Only unclaimed schedules can be cancelled; stop a running Run separately");
      history(id,"CANCELLED","");
    });
  }
  private void transition(String project,String id,String from,String to) {
    transaction.executeWithoutResult(status->{get(project,id);
      if(db.sql("UPDATE agent_schedules SET status=? WHERE project=? AND id=? AND status=?").params(to,project,id,from).update()!=1)
        throw new IllegalStateException("Schedule already activated or terminal");
      history(id,to,"");
    });
  }
  public Optional<Entry> claimDue() {
    expireEventWaits();
    return transaction.execute(status->{
      var result=db.sql("UPDATE agent_schedules SET status='RUNNING',run=? WHERE id=(SELECT candidate.id FROM agent_schedules candidate WHERE candidate.status='SCHEDULED' AND candidate.due<=? AND NOT EXISTS(SELECT 1 FROM agent_schedules active WHERE active.project=candidate.project AND active.session=candidate.session AND active.status='RUNNING') ORDER BY candidate.due,candidate.id LIMIT 1) AND status='SCHEDULED' RETURNING *")
          .params(UUID.randomUUID().toString(),clock.millis()).query(ROW).optional();
      result.ifPresent(entry->history(entry.task().id(),"RUNNING",entry.runId()));return result;
    });
  }
  public boolean activeClaim(Entry claim) {
    return db.sql("SELECT COUNT(*) FROM agent_schedules WHERE project=? AND id=? AND run=? AND status='RUNNING'")
        .params(claim.projectId(),claim.task().id(),claim.runId()).query(Integer.class).single()==1;
  }
  /** Explicit human reconciliation; unknown effects are never reported as successful or replayed. */
  public Entry reconcile(String project,String id,String expectedRunId) {
    transaction.executeWithoutResult(status->{
      get(project,id);
      if(db.sql("UPDATE agent_schedules SET status='FAILED',outcome='uncertain_run_reconciled' WHERE project=? AND id=? AND run=? AND status='RUNNING'")
          .params(project,id,expectedRunId).update()!=1)throw new IllegalStateException("Schedule is no longer running with the specified Run ID");
      history(id,"FAILED","uncertain_run_reconciled");
    });return get(project,id);
  }
  public void finish(Entry claim,String state,String detail) {
    if(!Set.of("COMPLETED","FAILED","CANCELLED").contains(state))throw new IllegalArgumentException("Invalid terminal status");
    transaction.executeWithoutResult(status->{
      String outcome=preview(detail);
      if(db.sql("UPDATE agent_schedules SET status=?,outcome=? WHERE id=? AND project=? AND run=? AND status='RUNNING'")
          .params(state,outcome,claim.task().id(),claim.projectId(),claim.runId()).update()!=1)throw new IllegalStateException("Claim no longer active");
      history(claim.task().id(),state,outcome);
      if(state.equals("COMPLETED")) {
        var interval=db.sql("SELECT interval_ms,remaining FROM agent_schedule_intervals WHERE id=?")
            .param(claim.task().id()).query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2)}).optional();
        if(interval.isPresent()) {
          long[] repeat=interval.get();
          db.sql("UPDATE agent_schedule_intervals SET remaining=remaining-1 WHERE id=?").param(claim.task().id()).update();
          if(repeat[1]>1)scheduleNext(claim.task().id(),clock.instant().plusMillis(repeat[0]),"interval continuation; missed occurrences coalesced");
        }
        var cron=cron(claim.projectId(),claim.task().id());
        if(cron.isPresent()) {
          var repeat=cron.get();
          db.sql("UPDATE agent_schedule_crons SET remaining=remaining-1 WHERE id=?").param(claim.task().id()).update();
          if(repeat.remaining()>1) {
            Instant due=nextCron(repeat.expression(),repeat.zone(),clock.instant());
            if(due!=null&&due.isAfter(clock.instant())&&!due.isAfter(clock.instant().plus(Duration.ofDays(366))))
              scheduleNext(claim.task().id(),due,"cron continuation; missed occurrences coalesced");
          }
        }
      }
    });
  }
  public List<History> history(String project,String id) {
    get(project,id);return db.sql("SELECT * FROM agent_schedule_history WHERE id=? ORDER BY sequence").param(id)
        .query((rs,n)->new History(rs.getString("status"),Instant.ofEpochMilli(rs.getLong("timestamp")),rs.getString("detail"))).list();
  }
  private void history(String id,String status,String detail) {db.sql("INSERT INTO agent_schedule_history(id,status,timestamp,detail) VALUES(?,?,?,?)").params(id,status,clock.millis(),preview(detail)).update();}
  static String preview(String text) {String safe=CredentialRedactor.redact(text==null?"":text);return safe.substring(0,Math.min(512,safe.length()));}
}
