package dev.mikoto2000.rei.llm.capture;

import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Bounded volatile originals. No body-bearing object has a generated toString. */
@Component
public final class CaptureStore {
  public static final int BODY_LIMIT=1_048_576, RUN_LIMIT=16_777_216, ATTEMPT_LIMIT=32;
  private final java.util.concurrent.Semaphore copyPermit=new java.util.concurrent.Semaphore(1);
  boolean tryCopy(){return copyPermit.tryAcquire();}
  void releaseCopy(){copyPermit.release();}
  private final Clock clock;
  private Reservation reservation;
  private final LinkedHashMap<String,Session> sessions=new LinkedHashMap<>();
  private record Reservation(String captureId,Object client,String conversation) {}
  public record SessionInfo(String captureId,String conversationId,String submissionId,String runId,String rootRunId,
      String parentRunId,int attempts,int bytes,boolean incomplete,Instant ended,String outcome) {}
  public record AttemptInfo(String runId,String logicalCallId,String attemptId,int attemptNumber,String sendState,
      String captureState,int size,String sha256,String contentType,Instant capturedAt,Integer httpStatus,String reason) {}
  public static final class LogicalCall {
    final String runId,id=UUID.randomUUID().toString(); final AtomicInteger attempts=new AtomicInteger();
    LogicalCall(String runId){this.runId=runId;}
  }
  private static final class Attempt {
    AttemptInfo info; byte[] body;
    Attempt(AttemptInfo info,byte[] body){this.info=info;this.body=body;}
  }
  private static final class Session {
    final String captureId,conversation,submission,run;
    final LinkedHashMap<String,Attempt> attempts=new LinkedHashMap<>();
    int bytes;boolean incomplete;Instant ended;String outcome="ACTIVE";
    Session(Reservation r,String submission,String run){captureId=r.captureId;conversation=r.conversation;this.submission=submission;this.run=run;}
    SessionInfo info(){return new SessionInfo(captureId,conversation,submission,run,run,null,attempts.size(),bytes,incomplete,ended,outcome);}
  }
  public CaptureStore(){this(Clock.systemUTC());}
  public CaptureStore(Clock clock){this.clock=Objects.requireNonNull(clock);}
  public synchronized void reserve(Object client,String conversation){
    prune();Objects.requireNonNull(client);Objects.requireNonNull(conversation);
    if(reservation!=null || sessions.values().stream().anyMatch(s->s.ended==null))throw new IllegalStateException("Capture reservation or recording already active");
    reservation=new Reservation(UUID.randomUUID().toString(),client,conversation);
  }
  public synchronized boolean off(Object client,String conversation){
    if(matches(client,conversation)){reservation=null;return true;}return false;
  }
  public synchronized String status(Object client,String conversation){
    prune();return (matches(client,conversation)?"RESERVED":"OFF")+"; sessions="+sessions.size()+"; stored bytes="+sessions.values().stream().mapToInt(s->s.bytes).sum()
        +"; active="+sessions.values().stream().filter(s->s.ended==null).map(s->s.run).findFirst().orElse("none")
        +"; limits: body=1048576/run=16777216/attempts=32/sessions=3/TTL=30min";
  }
  private boolean matches(Object client,String conversation){return reservation!=null && Objects.equals(reservation.client,client)&&Objects.equals(reservation.conversation,conversation);}
  public synchronized boolean accept(Object client,String conversation,String submission,String run){
    prune();if(!matches(client,conversation))return false;
    while(sessions.size()>=3){var key=sessions.entrySet().stream().filter(e->e.getValue().ended!=null).map(Map.Entry::getKey).findFirst().orElseThrow();erase(sessions.remove(key));}
    sessions.put(run,new Session(reservation,submission,run));reservation=null;return true;
  }
  /** Only a submitted exact root is eligible; callers explicitly exclude children. */
  public synchronized LogicalCall logicalCall(String run,String parent){
    prune();var s=sessions.get(run);return parent==null && s!=null && s.ended==null?new LogicalCall(run):null;
  }
  public synchronized AttemptInfo prepare(LogicalCall call,byte[] bytes,String contentType,String skipped){
    if(call==null)return null;prune();var s=sessions.get(call.runId);if(s==null||s.ended!=null)return null;
    int number=call.attempts.incrementAndGet();
    if(s.attempts.size()>=ATTEMPT_LIMIT){s.incomplete=true;return null;}
    String state=skipped==null?"COMPLETE":skipped,reason=null;byte[] body=null;String hash=null;
    int size=bytes==null?-1:bytes.length;
    if(skipped==null && (bytes==null || size>BODY_LIMIT || size>RUN_LIMIT-s.bytes)){
      state=bytes==null?"CAPTURE_FAILED":"SKIPPED_TOO_LARGE";reason=bytes==null?"Body unavailable":"Body or Run capacity exceeded";
    } else if(skipped==null){
      try{body=bytes.clone();hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));s.bytes+=size;}
      catch(java.security.NoSuchAlgorithmException unavailable){state="CAPTURE_FAILED";body=null;reason="Hash unavailable";}
    }
    if(!"COMPLETE".equals(state)){s.incomplete=true;if(reason==null)reason=state;}
    var info=new AttemptInfo(s.run,call.id,UUID.randomUUID().toString(),number,"PREPARED",state,size,hash,contentType,clock.instant(),null,reason);
    s.attempts.put(info.attemptId(),new Attempt(info,body));return info;
  }
  public synchronized void outcome(String id,String state,Integer status){
    if(id==null)return;var a=findExact(id);if(a==null)return;var i=a.info;
    a.info=new AttemptInfo(i.runId(),i.logicalCallId(),i.attemptId(),i.attemptNumber(),state,i.captureState(),i.size(),i.sha256(),i.contentType(),i.capturedAt(),status,i.reason());
  }
  public synchronized boolean canCopy(LogicalCall call){var s=sessions.get(call.runId);return s!=null&&s.ended==null&&s.attempts.size()<ATTEMPT_LIMIT&&s.bytes<RUN_LIMIT;}
  public synchronized boolean enabled(LogicalCall call){prune();var s=sessions.get(call.runId);return s!=null&&s.ended==null;}
  public synchronized void missed(LogicalCall call){var s=sessions.get(call.runId);if(s!=null)s.incomplete=true;}
  public synchronized void cancel(LogicalCall call){var s=sessions.get(call.runId);if(s!=null)s.attempts.values().stream().filter(a->a.info.logicalCallId().equals(call.id)&&List.of("PREPARED","SEND_STARTED").contains(a.info.sendState())).forEach(a->outcome(a.info.attemptId(),"CANCELLED",null));}
  public synchronized void finish(String run){finish(run,"ENDED");}
  public synchronized void finish(String run,String outcome){
    var s=sessions.get(run);if(s!=null&&s.ended!=null){if(s.outcome.equals("ENDED")&&!outcome.equals("ENDED"))s.outcome=outcome;return;}if(s!=null){s.ended=clock.instant();s.outcome=outcome;
      s.attempts.values().stream().filter(a->List.of("PREPARED","SEND_STARTED").contains(a.info.sendState())).forEach(a->outcome(a.info.attemptId(),"OUTCOME_UNKNOWN",null));}
  }
  public synchronized void startFailed(String run){finish(run,"START_FAILED");}
  public synchronized List<SessionInfo> sessions(){prune();return sessions.values().stream().map(Session::info).toList();}
  public synchronized List<AttemptInfo> attempts(String run){prune();var s=resolveSession(run);return s.attempts.values().stream().map(a->a.info).toList();}
  public synchronized AttemptInfo attempt(String id){prune();return resolveAttempt(id).info;}
  public synchronized byte[] body(String id){prune();var a=resolveAttempt(id);if(a.body==null||!a.info.captureState().equals("COMPLETE"))throw new IllegalArgumentException("Complete original unavailable");return a.body.clone();}
  private Session resolveSession(String id){var found=sessions.values().stream().filter(s->s.run.equals(id)||id.length()>=8&&s.run.startsWith(id)).toList();if(found.size()!=1)throw new IllegalArgumentException("Unknown or ambiguous Run ID");return found.getFirst();}
  private Attempt resolveAttempt(String id){var found=sessions.values().stream().flatMap(s->s.attempts.values().stream()).filter(a->a.info.attemptId().equals(id)||id.length()>=8&&a.info.attemptId().startsWith(id)).toList();if(found.size()!=1)throw new IllegalArgumentException("Unknown or ambiguous Attempt ID");return found.getFirst();}
  private Attempt findExact(String id){return sessions.values().stream().map(s->s.attempts.get(id)).filter(Objects::nonNull).findFirst().orElse(null);}
  public synchronized boolean delete(String run){prune();Session s;try{s=resolveSession(run);}catch(IllegalArgumentException unknown){return false;}sessions.remove(s.run);erase(s);return true;}
  public synchronized void clear(){reservation=null;sessions.values().forEach(CaptureStore::erase);sessions.clear();}
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=1000)
  public synchronized void prune(){var iterator=sessions.values().iterator();while(iterator.hasNext()){var s=iterator.next();if(s.ended!=null&&!s.ended.plus(Duration.ofMinutes(30)).isAfter(clock.instant())){erase(s);iterator.remove();}}}
  private static void erase(Session s){s.attempts.values().forEach(a->{if(a.body!=null)Arrays.fill(a.body,(byte)0);a.body=null;});s.attempts.clear();}
}
