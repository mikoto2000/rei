package dev.mikoto2000.rei.memory.service;

import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.memory.model.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;

/** Trigger-independent consolidation. No CLI, timers or history writes. */
@Service
public class SleepService {
  private static final Set<String> RUNNING=ConcurrentHashMap.newKeySet();
  private final MemoryRepository repository;
  private final ConversationTurnStore turns;
  private final MemoryCandidateExtractor extractor;
  private final MemoryResolver resolver;
  private final MemoryProperties properties;
  private MemoryEvents events;
  @org.springframework.beans.factory.annotation.Autowired
  public void setEvents(MemoryEvents events) { this.events=events; }
  public record Plan(MemoryCandidate candidate, MemoryResolution resolution, List<LongTermMemory> targets) {
    public Plan { targets=List.copyOf(targets); }
  }
  public record Report(SleepRun run, List<Plan> plans, boolean preview) {
    public Report { plans=List.copyOf(plans); }
  }
  public SleepService(MemoryRepository repository, ConversationTurnStore turns, MemoryCandidateExtractor extractor,
      MemoryResolver resolver, MemoryProperties properties) {
    this.repository=repository; this.turns=turns; this.extractor=extractor; this.resolver=resolver; this.properties=properties;
  }
  public Report sleep(String session, String project, boolean preview) {
    return sleep(session,project,preview,()->false);
  }
  public Report sleep(String session, String project, boolean preview, java.util.function.BooleanSupplier cancellation) {
    Runnable check=()-> {check(); if(cancellation.getAsBoolean()) throw new CancellationException("Sleep cancelled by activity");};
    if (!properties.enabled()) throw new IllegalStateException("Memory is disabled (rei.memory.enabled=false)");
    if (session==null || session.isBlank() || project==null || project.isBlank()) throw new IllegalArgumentException("Select a session and project first");
    String sessionProject=dev.mikoto2000.rei.core.project.ProjectStorage.projectId(session);
    if (sessionProject!=null && !sessionProject.equals(project)) throw new IllegalArgumentException("Session belongs to another project");
    if (!RUNNING.add(session)) throw new IllegalStateException("Sleep already running for this session");
    String id="sleep_"+UUID.randomUUID(), started=java.time.Instant.now().toString();
    long from=0, to=0;
    int processed=0;
    try {
      check.run();
      if(events!=null) events.publish(dev.mikoto2000.rei.event.AgentEventType.MEMORY_SLEEP_STARTED,project,session,id,preview,0,0,0,"STARTED");
      from=repository.lastProcessed(session); to=from;
      var snapshot=turns.read(session);
      if (from>snapshot.size()) throw new IllegalStateException("History precedes saved Sleep checkpoint");
      var batch=new ArrayList<ConversationTurnStore.Turn>();
      int tokens=0;
      for (int i=(int)from;i<snapshot.size() && i-from<properties.sleep().maxTurns();i++) {
        var turn=snapshot.get(i);
        if (turn.status()==ConversationTurnStore.Status.RUNNING) break;
        int size=TokenEstimator.conservative().text(turn.request())+TokenEstimator.conservative().text(turn.assistantMessage())+64;
        if (tokens+size>properties.sleep().maxInputTokens()) {
          if (i==from) throw new IllegalStateException("Turn exceeds rei.memory.sleep.max-input-tokens; increase the limit to process it");
          break;
        }
        tokens+=size; to=i+1;
        // Cancelled/failed turns are terminal but not evidence of agreed decisions.
        if (turn.status()==ConversationTurnStore.Status.COMPLETED) batch.add(turn);
      }
      processed=(int)(to-from);
      dev.mikoto2000.rei.llm.ModelCallBudget budget=properties.sleep().maxLlmCallsPerProject()>0||properties.sleep().maxTotalTokensPerProject()>0
          ?new PersistentSleepModelBudget(repository,properties.sleep(),project,check)
          :properties.sleep().maxLlmCalls()>0||properties.sleep().maxTotalTokens()>0
              ?new SleepModelBudget(properties.sleep(),check):null;
      var candidates=batch.isEmpty()?List.<MemoryCandidate>of():budget==null?extractor.extract(List.copyOf(batch)):extractor.extract(List.copyOf(batch),budget);
      check.run();
      var sourceIds=batch.stream().map(ConversationTurnStore.Turn::runId).collect(java.util.stream.Collectors.toSet());
      var plans=new ArrayList<Plan>();
      var seen=new HashSet<String>();
      var usedTargets=new HashSet<String>();
      for (var candidate:candidates) {
        check.run();
        if (!sourceIds.containsAll(candidate.sourceTurnIds())) throw new IllegalArgumentException("Candidate references a turn outside the selected range");
        var pool=new LinkedHashMap<String,LongTermMemory>();
        repository.exact(candidate,project).ifPresent(m -> pool.put(m.id(),m));
        repository.search(candidate.content()+" "+candidate.summary()+" "+String.join(" ",candidate.tags()),project,50)
            .forEach(m -> pool.put(m.id(),m));
        // Include recent facts even when a semantic paraphrase shares no lexical terms.
        repository.list(project,50,0).forEach(m -> pool.putIfAbsent(m.id(),m));
        var existing=new ArrayList<LongTermMemory>();
        int existingTokens=TokenEstimator.conservative().text(candidate.content())+512;
        for(var m:pool.values()) {
          int size=TokenEstimator.conservative().text(m.content()+m.summary()+m.id())+64;
          if(existingTokens+size>properties.sleep().maxInputTokens()) break;
          existing.add(m); existingTokens+=size;
        }
        var resolution=!seen.add(candidate.scope()+":"+MemoryResolver.normalize(candidate.content()))
            ?new MemoryResolution(MemoryAction.IGNORE,List.of()):budget==null?resolver.resolve(candidate,existing,project):resolver.resolve(candidate,existing,project,budget);
        var targets=resolution.targetIds().stream().map(target -> repository.find(target).orElseThrow()).toList();
        if (resolution.targetIds().stream().anyMatch(usedTargets::contains))
          throw new IllegalArgumentException("Ambiguous batch: multiple changes to the same memory; retry with fewer turns");
        usedTargets.addAll(resolution.targetIds());
        plans.add(new Plan(candidate,resolution,targets));
      }
      var run=run(id,session,project,started,preview?"PREVIEW":"COMPLETED",from,to,processed,plans,0);
      if (!preview && processed>0) {
        final long checkpoint=from;
        repository.transaction(() -> {
          check.run();
          if (repository.lastProcessed(session)!=checkpoint) throw new IllegalStateException("Sleep checkpoint changed; retry");
          for (var plan:plans) {
            check.run();
            for (var target:plan.targets()) if (!repository.find(target.id()).orElseThrow().equals(target))
              throw new IllegalStateException("Memory changed during Sleep; retry");
            apply(plan,project,session);
          }
          check.run(); repository.saveRun(run); check.run(); return null;
        });
      }
      if(events!=null) events.publish(dev.mikoto2000.rei.event.AgentEventType.MEMORY_SLEEP_COMPLETED,project,session,id,preview,processed,plans.size(),run.added(),run.status());
      return new Report(run,plans,preview);
    } catch (RuntimeException error) {
      if(events!=null) events.publish(dev.mikoto2000.rei.event.AgentEventType.MEMORY_SLEEP_FAILED,project,session,id,preview,processed,0,0,
          RunCancellation.isCancellation(error)||Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED");
      if (!preview) {
        boolean cancelled=RunCancellation.isCancellation(error)||Thread.currentThread().isInterrupted();
        // Clear interrupt only around the failure audit; restore before propagating cancellation.
        boolean interrupted=Thread.interrupted();
        try { repository.saveRun(run(id,session,project,started,cancelled?"CANCELLED":"FAILED",from,to,processed,List.of(),1)); }
        catch (RuntimeException audit) { error.addSuppressed(audit); }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
      }
      RunCancellation.propagate(error); throw error;
    } finally { RUNNING.remove(session); }
  }
  private void apply(Plan plan,String project,String session) {
    var c=plan.candidate(); var r=plan.resolution();
    switch(r.action()) {
      case IGNORE -> { }
      case NEW -> repository.insert(c,project,session);
      case DUPLICATE -> repository.enrich(r.targetIds().getFirst(),c,session);
      case UPDATE -> repository.update(r.targetIds().getFirst(),c,session);
      case MERGE,SUPERSEDE,CONFLICT -> {
        var added=repository.insert(c,project,session);
        for(String target:r.targetIds()) {
          if(r.action()==MemoryAction.CONFLICT) repository.relate(target,added.id(),"CONFLICT");
          else {
            if(r.action()==MemoryAction.MERGE) repository.copySources(target,added.id());
            repository.supersede(target,added.id(),r.action().name());
          }
        }
      }
    }
  }
  private static SleepRun run(String id,String session,String project,String started,String status,long from,long to,
      int processed,List<Plan> plans,int failed) {
    var counts=new EnumMap<MemoryAction,Integer>(MemoryAction.class);
    for(var plan:plans) counts.merge(plan.resolution().action(),1,Integer::sum);
    return new SleepRun(id,session,project,started,java.time.Instant.now().toString(),status,from+1,to,processed,plans.size(),
        counts.getOrDefault(MemoryAction.NEW,0),counts.getOrDefault(MemoryAction.UPDATE,0),counts.getOrDefault(MemoryAction.MERGE,0),
        counts.getOrDefault(MemoryAction.SUPERSEDE,0),counts.getOrDefault(MemoryAction.IGNORE,0)+counts.getOrDefault(MemoryAction.DUPLICATE,0),
        counts.getOrDefault(MemoryAction.CONFLICT,0),failed);
  }
  public long unsleptTurns(String session) { return session==null?0:Math.max(0,turns.read(session).size()-repository.lastProcessed(session)); }
  private static void check() { if(Thread.currentThread().isInterrupted()) throw new CancellationException("Sleep cancelled"); }
}
