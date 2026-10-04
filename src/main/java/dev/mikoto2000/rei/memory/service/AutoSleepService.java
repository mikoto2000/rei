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
  private long runningVersion;
  private boolean closed;
  public AutoSleepService(SleepService sleep, MemoryProperties memory, AutoSleepProperties properties,
      AgentActivityTracker activity, Clock clock) {
    this.sleep=sleep; this.memory=memory; this.properties=properties; this.activity=activity; this.clock=clock;
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
    for(var session:sessions.entrySet()) {
      Instant previous=attempts.get(session.getKey());
      if(previous!=null && now.isBefore(previous.plus(properties.retryInterval()))) continue;
      try {
        if(sleep.unsleptTurns(session.getKey())<properties.minimumTurns()) continue;
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
  @jakarta.annotation.PreDestroy @Override public void close() {
    synchronized(this) {if(closed)return; closed=true; worker.shutdown();}
    try {if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}
    catch(InterruptedException error) {worker.shutdownNow(); Thread.currentThread().interrupt();}
  }
}
