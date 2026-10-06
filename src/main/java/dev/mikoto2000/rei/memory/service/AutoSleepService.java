package dev.mikoto2000.rei.memory.service;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.topic.AgentActivityTracker;

/** One bounded idle batch at a time; persistent Sleep cursors remain the source of truth. */
@Component
@EnableConfigurationProperties(AutoSleepProperties.class)
public class AutoSleepService implements AutoCloseable {
  private final SleepService sleep;
  private final MemoryProperties memory;
  private final AutoSleepProperties properties;
  private final AgentActivityTracker activity;
  private final Clock clock;
  private final Map<String,String> sessions=new LinkedHashMap<>();
  private final Map<String,Instant> attempts=new HashMap<>();
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> {
    var thread=new Thread(r,"rei-auto-sleep"); thread.setDaemon(true); return thread;
  });
  private volatile Thread runningThread;
  private final java.util.concurrent.atomic.AtomicBoolean executing=new java.util.concurrent.atomic.AtomicBoolean();
  private dev.mikoto2000.rei.application.session.SessionRepository savedSessions;
  private dev.mikoto2000.rei.core.project.ProjectService projects;
  private Iterator<dev.mikoto2000.rei.application.session.SessionMetadata> startup;
  private boolean startupCaptured;
  private Instant nextMetadataScan;
  private dev.mikoto2000.rei.application.session.CursorKey metadataCursor;
  private boolean metadataScanning;
  @org.springframework.beans.factory.annotation.Autowired
  public synchronized void setStartupSources(dev.mikoto2000.rei.application.session.SessionRepository savedSessions,
      dev.mikoto2000.rei.core.project.ProjectService projects) {
    this.savedSessions=savedSessions;this.projects=projects;
  }
  /** Metadata only: no history reads or model calls until the usual idle gates pass. */
  private void discoverStartup() {
    if(savedSessions==null||projects==null)return;
    if(!startupCaptured) {startup=savedSessions.completionSnapshot().iterator();startupCaptured=true;}
    var registered=projects.completionProjects();
    for(int scanned=0;scanned<256&&sessions.size()<256&&startup.hasNext();scanned++) {
      var item=startup.next();
      registerMetadata(item,registered);
    }
    if(!startup.hasNext())startup=Collections.emptyIterator();
  }
  private void registerMetadata(dev.mikoto2000.rei.application.session.SessionMetadata item,
      List<dev.mikoto2000.rei.core.project.ProjectContext> registered) {
    if(item.sessionId().isBlank()||item.projectId().isBlank()||registered.stream().noneMatch(p->p.id().equals(item.projectId())))return;
    try {
      String encoded=dev.mikoto2000.rei.core.project.ProjectStorage.projectId(item.sessionId());
      if(encoded!=null&&!encoded.equals(item.projectId()))return;
      sessions.putIfAbsent(item.sessionId(),item.projectId());
    } catch(IllegalArgumentException invalidIdentity) { /* Unusable metadata is never a Sleep candidate. */ }
  }
  /** Periodic persistent reads catch metadata added after startup, without an unbounded candidate queue. */
  private void discoverSaved(Instant now) {
    discoverStartup();
    if(!startupCaptured||startup.hasNext()||sessions.size()>=256)return;
    if(!metadataScanning) {
      if(nextMetadataScan==null) {nextMetadataScan=now.plus(properties.retryInterval());return;}
      if(now.isBefore(nextMetadataScan))return;
      metadataScanning=true;metadataCursor=null;
    }
    try {
      int limit=Math.min(100,256-sessions.size());
      var rows=savedSessions.findPage(null,metadataCursor,limit);
      if(rows.size()>limit)throw new IllegalStateException("Session metadata page exceeds limit");
      var registered=projects.completionProjects();
      for(var item:rows)registerMetadata(item,registered);
      if(rows.size()<limit) {
        metadataScanning=false;metadataCursor=null;nextMetadataScan=now.plus(properties.retryInterval());
      } else {
        var last=rows.getLast();
        var next=new dev.mikoto2000.rei.application.session.CursorKey(last.updatedAt(),last.sessionId());
        if(next.equals(metadataCursor))throw new IllegalStateException("Session metadata cursor did not advance");
        metadataCursor=next;
      }
    } catch(RuntimeException error) {
      metadataScanning=false;metadataCursor=null;nextMetadataScan=now.plus(properties.retryInterval());
      throw error;
    }
  }
  private final org.springframework.scheduling.support.CronExpression cron;
  private Instant nextCron;
  private long runningVersion;
  private boolean closed;
  public AutoSleepService(SleepService sleep, MemoryProperties memory, AutoSleepProperties properties,
      AgentActivityTracker activity, Clock clock) {
    this.sleep=sleep; this.memory=memory; this.properties=properties; this.activity=activity; this.clock=clock;
    cron=properties.cron()==null?null:org.springframework.scheduling.support.CronExpression.parse(properties.cron());
    if(cron!=null)advanceCron(clock.instant());
  }
  /** Called only after durable terminal turn metadata. Never invokes the LLM on the user thread. */
  public synchronized void afterTerminal(AgentRunContext owner) {
    if (closed || !properties.enabled() || !memory.enabled() || owner.projectId()==null) return;
    sessions.put(owner.conversationId(),owner.projectId());
    while(sessions.size()>256) {
      String oldest=sessions.keySet().iterator().next(); sessions.remove(oldest); attempts.remove(oldest);
    }
  }
  @Scheduled(fixedDelayString="${rei.memory.auto-sleep.check-interval:5s}")
  public synchronized void tick() {
    if(closed) return;
    if(executing.get()) {
      Thread thread=runningThread;
      if(thread!=null && (activity.isAgentBusy() || activity.activityVersion()!=runningVersion)) thread.interrupt();
      return;
    }
    if(!properties.enabled() || !memory.enabled() || activity.isAgentBusy()) return;
    long version=activity.activityVersion();
    Instant now=clock.instant();
    Instant latest=java.util.stream.Stream.of(activity.applicationStartedAt(),activity.lastUserActivityAt(),activity.lastAgentActivityAt())
        .filter(Objects::nonNull).max(Instant::compareTo).orElse(now);
    if(Duration.between(latest,now).compareTo(properties.minimumIdle())<0) return;
    if(cron!=null&&(nextCron==null||now.isBefore(nextCron)))return;
    // Coalesce missed occurrences into one idle opportunity; never replay a backlog.
    if(cron!=null)advanceCron(now);
    try {discoverSaved(now);}
    catch(RuntimeException error) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Auto Sleep discovery unavailable ({})",error.getClass().getSimpleName());}
    var candidates=sessions.entrySet().iterator();
    while(candidates.hasNext()) {
      var session=candidates.next();
      Instant previous=attempts.get(session.getKey());
      if(previous!=null && now.isBefore(previous.plus(properties.retryInterval()))) continue;
      try {
        if(sleep.unsleptTurns(session.getKey())<properties.minimumTurns()) {attempts.remove(session.getKey());candidates.remove();continue;}
        String id=session.getKey(), project=session.getValue();
        runningVersion=version;
        attempts.put(id,now);
        executing.set(true);
        worker.submit(() -> {
          runningThread=Thread.currentThread();
          try {sleep.sleep(id,project,false,()->activity.isAgentBusy() || activity.activityVersion()!=version);}
          catch(RuntimeException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Auto Sleep deferred ({})",error.getClass().getSimpleName());
          }
          finally {runningThread=null; executing.set(false);}
        });
        return;
      } catch(RuntimeException error) {
        executing.set(false);
        attempts.put(session.getKey(),now);
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Auto Sleep check unavailable ({})",error.getClass().getSimpleName());
      }
    }
  }
  private void advanceCron(Instant after) {
    var next=cron.next(after.atZone(ZoneId.of(properties.zone())));
    nextCron=next==null?null:next.toInstant();
  }
  @jakarta.annotation.PreDestroy @Override public void close() {
    synchronized(this) {if(closed)return; closed=true; worker.shutdown();}
    try {if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}
    catch(InterruptedException error) {worker.shutdownNow(); Thread.currentThread().interrupt();}
  }
}
