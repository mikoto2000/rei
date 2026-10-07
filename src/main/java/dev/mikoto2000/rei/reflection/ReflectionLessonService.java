package dev.mikoto2000.rei.reflection;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.goal.*;

/** Human-reviewed, scoped recommendations. No automatic long-term memory promotion. */
@Service
@ConditionalOnProperty(name="rei.reflection.lessons-enabled",havingValue="true")
public class ReflectionLessonService {
  public enum Kind { FAILURE_PATTERN, SUCCESSFUL_STRATEGY, REPEATED_CORRECTION }
  public record Observation(String id,String state,String sourceKind,String sourceId,String origin,
      String expected,String actual,String sourceHash,Instant createdAt,boolean complete,boolean verified) {}
  public record Lesson(String id,String state,Kind kind,String statement,long revision,int occurrenceCount,
      List<String> evidence,String evidenceHash,List<String> counterexamples,List<String> corrections,String review) {
    public Lesson {evidence=List.copyOf(evidence);counterexamples=List.copyOf(counterexamples);corrections=List.copyOf(corrections);}
  }
  private final JdbcClient db;private final TransactionTemplate tx;private final RunReflectionRepository runs;
  private final GoalReflectionRepository reflections;private final GoalRepository goals;private final FileGoalVerifier verifier;
  private final Clock clock;private final boolean enabled;private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  public ReflectionLessonService(@Qualifier("memoryConsolidationDataSource") DataSource source,RunReflectionRepository runs,
      GoalReflectionRepository reflections,GoalRepository goals,FileGoalVerifier verifier,Clock clock,
      @Value("${rei.reflection.lessons-enabled:false}") boolean enabled) {
    db=JdbcClient.create(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.runs=runs;
    this.reflections=reflections;this.goals=goals;this.verifier=verifier;this.clock=clock;this.enabled=enabled;
    db.sql("CREATE TABLE IF NOT EXISTS reflection_lesson_observations(id TEXT PRIMARY KEY,owner TEXT NOT NULL,source TEXT NOT NULL,document TEXT NOT NULL,UNIQUE(owner,source))").update();
    db.sql("CREATE TABLE IF NOT EXISTS reflection_lessons(id TEXT PRIMARY KEY,owner TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS reflection_lesson_forgotten(owner TEXT NOT NULL,statement_hash TEXT NOT NULL,PRIMARY KEY(owner,statement_hash))").update();
  }
  private String owner(AgentRunContext context) {
    if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Reflection review cancelled");
    if(!enabled)throw new IllegalArgumentException("Reflection lessons are disabled");
    if(context.projectId()==null||context.mode()!=AgentRunContext.Mode.EXCLUSIVE)throw new IllegalArgumentException("Exclusive Project ownership required");
    try{return encode(List.of(context.projectId(),context.projectRoot().toRealPath().toString(),context.conversationId()));}
    catch(java.io.IOException error){throw new IllegalArgumentException("Project root unavailable",error);}
  }
  public Observation observe(AgentRunContext context,String kind,String id) {
    String scope=owner(context);Observation current=source(context,kind,id);
    return tx.execute(status->{
      var previous=db.sql("SELECT document FROM reflection_lesson_observations WHERE owner=? AND source=?").params(scope,kind+":"+id).query(String.class).optional();
      if(previous.isPresent())return decode(previous.get(),Observation.class);
      quota("reflection_lesson_observations",4096);
      db.sql("INSERT INTO reflection_lesson_observations VALUES(?,?,?,?)").params(current.id(),scope,kind+":"+id,encode(current)).update();return current;
    });
  }
  private Observation source(AgentRunContext context,String kind,String id) {
    if("USER_CORRECTION".equals(kind))return observation(owner(context),id);
    if("RUN".equals(kind)) {
      var item=runs.get(context.projectId(),id);if(!item.sessionId().equals(context.conversationId()))throw new IllegalArgumentException("Reflection session mismatch");
      return new Observation("observation-"+hash(owner(context)+":"+kind+":"+id),"OBSERVATION",kind,id,item.runId()==null?item.sourceId():item.runId(),
          item.expected(),item.status()+":"+item.gap(),hash(encode(item)),item.createdAt(),!item.actionsTruncated(),false);
    }
    if("GOAL".equals(kind)) {
      var item=reflections.get(context.projectId(),id);var goal=goals.get(context.projectId(),item.goalId());
      if(!item.sessionId().equals(context.conversationId())||!goal.sessionId().equals(context.conversationId())
          ||!goal.projectRoot().equals(context.projectRoot().toString()))throw new IllegalArgumentException("Goal ownership mismatch");
      boolean verified="VERIFIED".equals(item.actual())&&"COMPLETED".equals(goal.status())&&"COMPLETED".equals(item.goalStatus())
          &&Set.of("file_digest_verified","criteria_verified").contains(goal.reason())&&goal.reason().equals(item.actualReason())
          &&Objects.equals(goal.currentRunId()==null?"":goal.currentRunId(),item.runId())&&reflections.matchesCriteria(item,goal)&&verifier.verify(goal).satisfied();
      return new Observation("observation-"+hash(owner(context)+":"+kind+":"+id),"OBSERVATION",kind,id,item.goalId(),item.expectedFile(),item.actual()+":"+item.gap(),hash(encode(item)),item.createdAt(),true,verified);
    }
    throw new IllegalArgumentException("Unknown reflection source kind");
  }
  private Observation observation(String scope,String id) {return db.sql("SELECT document FROM reflection_lesson_observations WHERE owner=? AND id=?").params(scope,id).query(String.class).optional().map(value->decode(value,Observation.class)).orElseThrow(()->new IllegalArgumentException("Observation not found in this owner scope"));}
  public Observation observeCorrection(AgentRunContext context,String correctionId,String observationId,String correction) {
    String scope=owner(context);if(correctionId==null||!correctionId.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Stable correction ID required");
    var base=observation(scope,observationId);String text=note(correction);String documentHash=hash(encode(List.of(base.id(),base.sourceHash(),text)));
    return tx.execute(status->{
      String source="USER_CORRECTION:"+correctionId;var previous=db.sql("SELECT document FROM reflection_lesson_observations WHERE owner=? AND source=?").params(scope,source).query(String.class).optional();
      if(previous.isPresent()){var saved=decode(previous.get(),Observation.class);if(!saved.sourceHash().equals(documentHash))throw new IllegalArgumentException("Correction source cannot be replaced");return saved;}
      quota("reflection_lesson_observations",4096);String id="correction-"+hash(scope+":"+correctionId);
      var saved=new Observation(id,"OBSERVATION","USER_CORRECTION",id,base.origin(),base.actual(),text,documentHash,clock.instant(),base.complete(),false);
      db.sql("INSERT INTO reflection_lesson_observations VALUES(?,?,?,?)").params(id,scope,source,encode(saved)).update();return saved;
    });
  }
  public List<Lesson> list(AgentRunContext context){String scope=owner(context);return db.sql("SELECT document FROM reflection_lessons WHERE owner=? ORDER BY rowid DESC LIMIT 128").param(scope).query(String.class).list().stream().map(value->decode(value,Lesson.class)).toList();}
  public Lesson propose(AgentRunContext context,Kind kind,String statement,List<String> ids) {
    String scope=owner(context);Objects.requireNonNull(kind);String text=note(statement);
    if(ids==null||ids.isEmpty()||ids.size()>16||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("One to sixteen distinct observations required");
    var facts=ids.stream().map(id->observation(scope,id)).toList();
    return tx.execute(status->{
      if(db.sql("SELECT COUNT(*) FROM reflection_lesson_forgotten WHERE owner=? AND statement_hash=?").params(scope,statementHash(text)).query(Integer.class).single()>0)throw new IllegalArgumentException("Forgotten lesson cannot be regenerated");
      quota("reflection_lessons",1024);
      var lesson=new Lesson("lesson-"+UUID.randomUUID(),"CANDIDATE_LESSON",kind,text,0,(int)facts.stream().map(Observation::origin).distinct().count(),ids,hash(encode(facts)),List.of(),List.of(),"");
      db.sql("INSERT INTO reflection_lessons VALUES(?,?,?,?)").params(lesson.id(),scope,0,encode(lesson)).update();return lesson;
    });
  }
  public Lesson get(AgentRunContext context,String id) {String scope=owner(context);return read(scope,id);}
  private Lesson read(String scope,String id){return db.sql("SELECT document FROM reflection_lessons WHERE owner=? AND id=?").params(scope,id).query(String.class).optional().map(value->decode(value,Lesson.class)).orElseThrow(()->new IllegalArgumentException("Lesson not found in this owner scope"));}
  public Lesson validate(AgentRunContext context,String id,long revision,String evidenceHash,String review) {
    String scope=owner(context);String reviewed=note(review);var lesson=read(scope,id);check(lesson,revision);
    if(!lesson.state().equals("CANDIDATE_LESSON")||!lesson.evidenceHash().equals(evidenceHash)||lesson.occurrenceCount()<3||!lesson.counterexamples().isEmpty()||!lesson.corrections().isEmpty())throw new IllegalArgumentException("Lesson validation gates failed");
    for(String ref:lesson.evidence()) {
      var saved=observation(scope,ref);var current=source(context,saved.sourceKind(),saved.sourceId());
      if(!saved.sourceHash().equals(current.sourceHash())||!current.complete()||saved.createdAt().isAfter(clock.instant())||saved.createdAt().isBefore(clock.instant().minus(Duration.ofDays(30))))throw new IllegalArgumentException("Evidence is stale, incomplete or changed");
      if(lesson.kind()==Kind.SUCCESSFUL_STRATEGY&&!current.verified()||lesson.kind()==Kind.FAILURE_PATTERN&&!current.actual().startsWith("FAILED:")||lesson.kind()==Kind.REPEATED_CORRECTION&&!current.sourceKind().equals("USER_CORRECTION"))throw new IllegalArgumentException("Evidence does not support this lesson kind");
    }
    return tx.execute(status->{
      owner(context);
      if(db.sql("SELECT COUNT(*) FROM reflection_lesson_forgotten WHERE owner=? AND statement_hash=?").params(scope,statementHash(lesson.statement())).query(Integer.class).single()>0)throw new IllegalArgumentException("Forgotten lesson cannot be validated");
      return update(scope,lesson,new Lesson(id,"VALIDATED_LESSON",lesson.kind(),lesson.statement(),revision+1,lesson.occurrenceCount(),lesson.evidence(),lesson.evidenceHash(),lesson.counterexamples(),lesson.corrections(),reviewed));
    });
  }
  public Lesson counterexample(AgentRunContext context,String id,long revision,String observationId,String description) {
    String scope=owner(context);observation(scope,observationId);var lesson=read(scope,id);check(lesson,revision);
    var examples=new ArrayList<>(lesson.counterexamples());if(examples.size()>=16)throw new IllegalArgumentException("Counterexample quota exceeded");examples.add(observationId+": "+note(description));
    return update(scope,lesson,new Lesson(id,"REJECTED",lesson.kind(),lesson.statement(),revision+1,lesson.occurrenceCount(),lesson.evidence(),lesson.evidenceHash(),examples,lesson.corrections(),lesson.review()));
  }
  public Lesson correct(AgentRunContext context,String id,long revision,String correction) {
    String scope=owner(context);var lesson=read(scope,id);check(lesson,revision);var corrections=new ArrayList<>(lesson.corrections());if(corrections.size()>=16)throw new IllegalArgumentException("Correction quota exceeded");corrections.add(note(correction));
    return update(scope,lesson,new Lesson(id,"REJECTED",lesson.kind(),lesson.statement(),revision+1,lesson.occurrenceCount(),lesson.evidence(),lesson.evidenceHash(),lesson.counterexamples(),corrections,lesson.review()));
  }
  public Lesson forget(AgentRunContext context,String id,long revision) {
    String scope=owner(context);return tx.execute(status->{var lesson=read(scope,id);check(lesson,revision);
      db.sql("INSERT OR IGNORE INTO reflection_lesson_forgotten VALUES(?,?)").params(scope,statementHash(lesson.statement())).update();
      return update(scope,lesson,new Lesson(id,"REJECTED",lesson.kind(),lesson.statement(),revision+1,lesson.occurrenceCount(),lesson.evidence(),lesson.evidenceHash(),lesson.counterexamples(),lesson.corrections(),"FORGOTTEN"));});
  }
  private Lesson update(String scope,Lesson previous,Lesson next){if(db.sql("UPDATE reflection_lessons SET revision=?,document=? WHERE owner=? AND id=? AND revision=?").params(next.revision(),encode(next),scope,next.id(),previous.revision()).update()!=1)throw new IllegalArgumentException("Stale lesson revision");return next;}
  private void check(Lesson lesson,long revision){if(lesson.revision()!=revision)throw new IllegalArgumentException("Stale lesson revision");}
  private void quota(String table,int limit){if(db.sql("SELECT COUNT(*) FROM "+table).query(Integer.class).single()>=limit||db.sql("SELECT COALESCE(SUM(length(document)),0) FROM "+table).query(Long.class).single()>8*1024*1024)throw new IllegalArgumentException("Reflection ledger quota exceeded");}
  private String note(String value){if(value==null||value.isBlank()||value.length()>2048)throw new IllegalArgumentException("Nonblank note up to 2048 characters required");return RunReflectionRepository.safe(value,2048).strip();}
  private String statementHash(String value){return hash(value.strip().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT));}
  private String encode(Object value){try{String encoded=json.writeValueAsString(value);if(encoded.length()>65536)throw new IllegalArgumentException("Reflection record too large");return encoded;}catch(java.io.IOException error){throw new IllegalStateException(error);}}
  private <T>T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(java.io.IOException error){throw new IllegalStateException(error);}}
  private static String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
