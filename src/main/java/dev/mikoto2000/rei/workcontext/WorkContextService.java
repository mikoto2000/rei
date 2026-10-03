package dev.mikoto2000.rei.workcontext;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.workcontext.WorkContext.*;

/** Shared Shell/Chat Tool/HTTP application service. Extraction completes before any write. */
@Service
public class WorkContextService {
  private final WorkContextRepository repository;
  private final SessionRepository sessions;
  private final ProjectService projects;
  private final ConversationTurnStore turns;
  private final ProjectAgentEventStore events;
  private final WorkContextExtractor extractor;
  private final WorkContextGit git;
  private final WorkContextProperties properties;
  private final Clock clock;
  private final WorkContextMerger merger=new WorkContextMerger();
  private final java.util.concurrent.ConcurrentHashMap<String,Object> locks=new java.util.concurrent.ConcurrentHashMap<>();
  public WorkContextService(WorkContextRepository repository,SessionRepository sessions,ProjectService projects,
      ConversationTurnStore turns,ProjectAgentEventStore events,WorkContextExtractor extractor,WorkContextGit git,
      WorkContextProperties properties,Clock clock) {
    this.repository=repository;this.sessions=sessions;this.projects=projects;this.turns=turns;this.events=events;
    this.extractor=extractor;this.git=git;this.properties=properties;this.clock=clock;
  }
  public ProjectContext project(String id) {
    if(id==null||id.isBlank()) throw new IllegalArgumentException("プロジェクトを選択してください (/project cd <directory>)。");
    return projects.registeredProjects().stream().filter(p->p.id().equals(id)).findFirst()
        .orElseThrow(()->new IllegalArgumentException("Unknown registered project"));
  }
  public Optional<WorkContext> current(String project) { project(project);return repository.current(project); }
  public void captureRunStart(dev.mikoto2000.rei.core.chat.AgentRunContext context) {
    if(context.projectId()==null) return;
    var state=git.capture(context.projectRoot(),clock.instant());
    var metadata=new HashMap<String,String>();metadata.put("workContext.gitCapturedAt",state.capturedAt().toString());
    metadata.put("workContext.directory",state.directory());
    if(state.branch()!=null)metadata.put("workContext.branch",state.branch());
    if(state.commit()!=null)metadata.put("workContext.commit",state.commit());
    turns.recordMetadata(context,metadata);
  }
  public List<WorkContext> history(String project,int limit) { project(project);return repository.history(project,limit); }
  public WorkContext revision(String project,long revision) {
    project(project);return repository.revision(project,revision).orElseThrow(()->new dev.mikoto2000.rei.application.run.ResourceNotFoundException("Work Context revision"));
  }
  public Optional<WorkContext> update(String sessionId,String onlyRun) {
    return update(sessionId,onlyRun,false);
  }
  public Optional<WorkContext> update(String sessionId,String onlyRun,boolean includeRunning) {
    if(sessionId==null||sessionId.isBlank()) throw new IllegalArgumentException("Session を選択して会話を開始してください。");
    var session=sessions.findById(sessionId).orElseThrow(()->new IllegalArgumentException("Unknown session"));
    var project=project(session.projectId());
    if(!java.nio.file.Files.isDirectory(project.root())) throw new IllegalArgumentException("Project directory does not exist");
    synchronized(locks.computeIfAbsent(project.id(),id->new Object())) {
      WorkContextRepository.checkCancellation();
      var old=repository.current(project.id()).orElse(null);
      var selected=new ArrayList<ConversationTurnStore.Turn>();
      var history=turns.read(sessionId);
      String latestRun=history.stream().filter(t->t.source()==null).reduce((a,b)->b).map(ConversationTurnStore.Turn::runId).orElse(null);
      for(var turn:history) {
        if(turn.source()!=null) continue; // Proactive notifications are not user work instructions.
        if(onlyRun!=null&&!onlyRun.equals(turn.runId())) continue;
        if(turn.status()==ConversationTurnStore.Status.RUNNING&&(!includeRunning||!turn.runId().equals(latestRun))) continue;
        if(old!=null&&old.processedRuns().contains(turn.runId())) continue;
        selected.add(turn);if(selected.size()==properties.maxTurns()||turn.status()==ConversationTurnStore.Status.RUNNING) break;
      }
      if(selected.isEmpty()) return Optional.ofNullable(old);
      var evidence=new LinkedHashMap<String,Evidence>();
      Instant now=clock.instant();
      for(var turn:selected) {
        source(evidence,turn.runId()+":user",Origin.USER,sessionId,turn.runId(),null,turn.createdAt(),now,turn.request());
        source(evidence,turn.runId()+":assistant",Origin.ASSISTANT,sessionId,turn.runId(),null,turn.createdAt(),now,turn.assistantMessage());
        if(turn.status()!=ConversationTurnStore.Status.COMPLETED)
          source(evidence,turn.runId()+":terminal",Origin.TOOL,sessionId,turn.runId(),null,now,now,"Run terminal state: "+turn.status()+". Whole-task success was not verified.");
      }
      var ids=selected.stream().map(ConversationTurnStore.Turn::runId).collect(java.util.stream.Collectors.toSet());
      for(var event:events.recent(project.id(),1000)) {
        if(!sessionId.equals(event.sessionId())||!ids.contains(event.runId())) continue;
        if(event.payload() instanceof ToolCompletedPayload tool) {
          if(tool.toolName().startsWith("workContext")) continue;
          source(evidence,event.id(),Origin.TOOL,sessionId,event.runId(),tool.toolCallId(),event.timestamp(),now,
              tool.toolName()+": "+tool.resultSummary());
        } else if(event.payload() instanceof ToolFailedPayload tool)
          source(evidence,event.id(),Origin.TOOL,sessionId,event.runId(),tool.toolCallId(),event.timestamp(),now,
              "Tool failed: "+tool.toolName()+" "+tool.error());
        else if(event.payload() instanceof UserInterventionPayload user && event.type()==AgentEventType.USER_INTERVENTION_APPLIED)
          source(evidence,event.id(),Origin.USER,sessionId,event.runId(),null,event.timestamp(),now,user.text());
        else if(event.payload() instanceof AgentRunFailedPayload failed)
          source(evidence,event.id(),Origin.TOOL,sessionId,event.runId(),null,event.timestamp(),now,"Run interruption reason: "+failed.error());
        else {
          String path=switch(event.payload()) {
            case FileCreatedPayload file -> file.path();case FileModifiedPayload file -> file.path();case FileDeletedPayload file -> file.path();default -> null;
          };
          if(path!=null) evidence.put(event.id(),new Evidence(event.id(),Origin.TOOL,sessionId,event.turnId(),event.runId(),null,path,null,event.timestamp(),now,event.type().value()+": "+path));
        }
      }
      String statuses=selected.stream().map(t->t.runId()+":"+t.status()).collect(java.util.stream.Collectors.joining(","));
      var candidates=extractor.extract(List.copyOf(evidence.values()),old==null?List.of():old.items(),statuses);
      var unfinished=selected.stream().filter(t->t.status()!=ConversationTurnStore.Status.COMPLETED).map(ConversationTurnStore.Turn::runId).collect(java.util.stream.Collectors.toSet());
      candidates=candidates.stream().map(c->{
        var cited=c.sourceIds().stream().map(evidence::get).filter(Objects::nonNull).toList();
        boolean interrupted=cited.stream().anyMatch(e->unfinished.contains(e.runId()));
        boolean partialConfirmed=c.certainty()==Origin.USER||c.certainty()==Origin.TOOL&&cited.stream().anyMatch(e->e.toolCallId()!=null&&!e.text().startsWith("Tool failed:"));
        if(c.status()==Status.COMPLETED&&interrupted&&(c.kind()==Kind.CURRENT_WORK||!partialConfirmed))
          return new WorkContextCandidate(c.action(),c.targetId(),c.kind(),c.text(),c.reason(),Status.UNCONFIRMED,c.sourceIds(),c.certainty());
        return c;
      }).toList();
      WorkContextRepository.checkCancellation();
      var metadata=selected.getLast().metadata();
      var snapshot=metadata.containsKey("workContext.gitCapturedAt")?new GitState(metadata.get("workContext.directory"),metadata.get("workContext.branch"),metadata.get("workContext.commit"),Instant.parse(metadata.get("workContext.gitCapturedAt"))):git.capture(project.root(),now);
      var next=merger.merge(project.id(),old,null,candidates,evidence,snapshot,now);
      var processed=new HashSet<>(next.processedRuns());selected.stream().filter(t->t.status()!=ConversationTurnStore.Status.RUNNING).map(ConversationTurnStore.Turn::runId).forEach(processed::add);
      next=new WorkContext(next.projectId(),next.revision(),next.createdAt(),next.updatedAt(),next.git(),next.items(),processed);
      repository.save(next,old==null?0:old.revision());return Optional.of(next);
    }
  }
  private void source(Map<String,Evidence> target,String id,Origin origin,String session,String run,String tool,
      Instant observed,Instant acquired,String text) {
    if(text==null||text.isBlank()) return;
    String bounded=text.length()<=3000?text:text.substring(0,3000)+" [truncated evidence; no omitted outcome is verified]";
    target.put(id,new Evidence(id,origin,session,run,run,tool,null,null,observed,acquired,bounded));
  }
  /** Explicit user operation with a revision precondition: no silent stale correction. */
  public WorkContext edit(String projectId,long expected,String itemId,String action,String text,String reason,Status status,Evidence source) {
    project(projectId);
    if(source.origin()!=Origin.USER) throw new IllegalArgumentException("Explicit user correction required");
    synchronized(locks.computeIfAbsent(projectId,id->new Object())) {
      var old=repository.current(projectId).orElseThrow(()->new IllegalArgumentException("No Work Context"));
      if(old.revision()!=expected) throw new ConcurrentModificationException("Work Context changed; get current revision first");
      var item=old.items().stream().filter(i->i.id().equals(itemId)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown item"));
      var candidate=new WorkContextCandidate(action,itemId,item.kind(),text==null||text.isBlank()?item.text():text,
          Objects.toString(reason,""),status==null?item.status():status,List.of(source.id()));
      var next=merger.merge(projectId,old,null,List.of(candidate),Map.of(source.id(),source),null,clock.instant());
      repository.save(next,expected);return next;
    }
  }
}
