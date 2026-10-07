package dev.mikoto2000.rei.application.task;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;

/** Reads existing state without dispatch, reconciliation, or creating another Task lifecycle. */
public final class TaskManagerService {
  private final ProjectRegistry projects;
  private final RunRegistry runs;
  private final PersistentCheckpointRepository checkpoints;
  private final GoalRepository goals;
  private final PersistentDependencyRepository dependencies;
  private final PersistentAgentScheduler schedules;
  private final CursorCodec cursors=new CursorCodec();
  public TaskManagerService(ProjectRegistry projects,RunRegistry runs,PersistentCheckpointRepository checkpoints,
      GoalRepository goals,PersistentDependencyRepository dependencies,PersistentAgentScheduler schedules) {
    this.projects=projects;this.runs=runs;this.checkpoints=checkpoints;this.goals=goals;this.dependencies=dependencies;this.schedules=schedules;
  }
  public HistoryPage<TaskView> list(String project,String session,Integer requestedLimit,String cursor) {
    int limit=Pagination.limit(requestedLimit);
    if(project!=null&&project.isBlank()||session!=null&&(session.isBlank()||session.length()>256))throw new IllegalArgumentException("Invalid Task filter");
    String scope="tasks:"+Objects.toString(project,"")+":"+Objects.toString(session,"");
    var after=cursors.decode(scope,cursor);
    var allowed=project==null?projects.list():List.of(project(project));
    var rows=new TreeMap<String,TaskView>();
    for(var owner:allowed)for(var task:catalog(owner,session,after==null?null:after.id(),limit+1,null)) {
      if(session!=null&&!session.equals(task.sessionId())||after!=null&&task.id().compareTo(after.id())<=0)continue;
      if(rows.putIfAbsent(task.id(),task)!=null)throw new IllegalStateException("Duplicate projected Task identity");
      if(rows.size()>limit+1)rows.pollLastEntry();
    }
    return Pagination.page(List.copyOf(rows.values()),limit,row->cursors.encode(scope,new CursorKey(Instant.EPOCH,row.id())));
  }
  public TaskView get(String project,String session,String id) {
    if(id==null||id.isBlank()||id.length()>256)throw new IllegalArgumentException("Invalid Task identity");
    return catalog(project(project),session,null,1,id).stream().filter(task->task.id().equals(id)&&Objects.equals(session,task.sessionId()))
        .findFirst().orElseThrow(()->new ResourceNotFoundException("Task"));
  }
  private ProjectContext project(String id) {
    if(id==null||id.isBlank())throw new IllegalArgumentException("Project required");
    return projects.resolveById(id).orElseThrow(()->new ResourceNotFoundException("Task"));
  }
  private List<TaskView> catalog(ProjectContext project,String session,String after,int limit,String exact) {
    var result=new LinkedHashMap<String,TaskView>();
    var runTasks=new HashMap<String,String>();
    var checkpointRows=checkpoints==null?List.<dev.mikoto2000.rei.checkpoint.PersistentCheckpoint>of():sourceRows("run:",exact,after,
        key->checkpoints.taskPage(project.id(),project.root().toString(),session,key,limit),
        key->checkpoints.findByRun(project.id(),key).filter(saved->saved.originalRunId().equals(key)));
    for(var saved:checkpointRows) {
      if(!sameRoot(project,saved.projectRoot()))continue;
      String id="run:"+saved.originalRunId();
      for(String run:List.of(saved.originalRunId(),saved.runId()))runTasks.put(run,id);
      if(saved.resumedFromRunId()!=null)runTasks.put(saved.resumedFromRunId(),id);
      var current=run(project,saved.sessionId(),saved.runId());
      String state=current!=null?current.status().name():checkpointState(saved);
      int completed=(int)saved.plan().stream().filter(step->"DONE".equals(step.get("status"))).count();
      boolean live=current!=null&&!current.status().isTerminal();
      boolean resumable=!live&&!"COMPLETED".equals(state)&&!checkpoints.leased(project.id(),saved.taskId())&&!Set.of("COMPLETED","ABANDONED").contains(saved.status());
      String error=current!=null&&current.failure()!=null?current.failure().message():"UNKNOWN".equals(state)?"Execution result unknown; inspect checkpoint before explicit resume":saved.interruptionReason();
      result.put(id,new TaskView(id,"CHECKPOINT",saved.taskId(),project.id(),saved.sessionId(),saved.runId(),state,saved.mode().name(),
          current==null?null:current.startedAt(),saved.createdAt(),"QUEUED".equals(state)?"project_queue":String.join("; ",saved.blockers()),
          safe(error),new TaskView.Progress(completed,saved.plan().size()),List.of(new TaskView.Reference("CHECKPOINT",saved.taskId()),new TaskView.Reference("TURN",saved.runId())),
          null,List.of(),null,null,List.of(),saved.taskId(),live&&runs.ownsExecution(saved.runId()),resumable,live&&runs.ownsExecution(saved.runId())&&current.status()==RunStatus.RUNNING,saved.revision()));
    }
    var runKeys=exact==null?(lower("run:",after)==null?List.<String>of():runs.runIds().stream().sorted().toList()):exact.startsWith("run:")?List.of(exact.substring(4)):List.<String>of();
    int selectedRuns=0;
    for(String id:runKeys) {
      if(after!=null&&("run:"+id).compareTo(after)<=0)continue;
      var current=run(project,session,id);if(current==null||runTasks.containsKey(id))continue;
      if(checkpoints!=null&&checkpoints.findByRun(project.id(),id).filter(saved->sameRoot(project,saved.projectRoot())).isPresent())continue;
      if(selectedRuns++>=limit)break;
      var owner=current.context();String task="run:"+id;runTasks.put(id,task);
      result.put(task,new TaskView(task,current.childOrigin()==null?"RUN":"SUBAGENT",id,project.id(),session(current),id,current.status().name(),owner.mode().name(),
          current.startedAt(),current.completedAt()==null?current.startedAt():current.completedAt(),current.status()==RunStatus.QUEUED?"project_queue":null,
          current.failure()==null?null:safe(current.failure().message()),null,current.childOrigin()!=null?List.of(new TaskView.Reference("RUN",id)):owner.conversationId().isEmpty()?List.of():List.of(new TaskView.Reference("TURN",id)),
          null,List.of(),null,current.childOrigin()==null?null:referenceTask(project,current.childOrigin().parentRunId()),List.of(),null,!current.status().isTerminal()&&runs.ownsExecution(id),false,current.childOrigin()==null&&current.status()==RunStatus.RUNNING&&runs.ownsExecution(id),0));
    }
    var goalParents=new HashMap<String,String>();
    if(goals!=null)for(var goal:sourceRows("goal:",exact,after,key->goals.taskPage(project.id(),project.root().toString(),session,key,limit),key->existing(()->goals.get(project.id(),key)))) {
      if(!sameRoot(project,goal.projectRoot()))continue;
      var history=goals.history(project.id(),goal.id());
      Instant first=history.isEmpty()?null:history.getFirst().timestamp(),last=history.isEmpty()?null:history.getLast().timestamp();
      String id="goal:"+goal.id();
      var attempts=goals.attempts(project.id(),goal.id());
      var references=attempts.stream().map(attempt->new TaskView.Reference("RUN",attempt.runId())).toList();
      for(var attempt:attempts)if(runTasks.containsKey(attempt.runId()))goalParents.put(runTasks.get(attempt.runId()),id);
      if(goal.currentRunId()!=null&&runTasks.containsKey(goal.currentRunId()))goalParents.put(runTasks.get(goal.currentRunId()),id);
      String state=switch(goal.status()){case "PAUSED"->"SUSPENDED";case "READY"->"QUEUED";case "WAITING_APPROVAL"->"WAITING";default->goal.status();};
      var current=run(project,goal.sessionId(),goal.currentRunId());
      if(state.equals("RUNNING")&&(current==null||current.status()==RunStatus.UNKNOWN))state="UNKNOWN";
      boolean controlsOwned=!goal.status().equals("RUNNING")||current!=null&&!current.status().isTerminal()&&runs.ownsExecution(goal.currentRunId());
      boolean resumable=Set.of("READY","PAUSED","BLOCKED","FAILED","WAITING_APPROVAL").contains(goal.status())
          &&goal.attempts()<goal.maxRuns()&&goal.llmCallsUsed()<goal.maxLlmCalls()
          &&(goal.maxTotalTokens()==0||!goal.tokenUsageUnknown()&&goal.pendingLlmCalls()==0&&goal.totalTokens()<goal.maxTotalTokens());
      result.put(id,new TaskView(id,"GOAL",goal.id(),project.id(),goal.sessionId(),goal.currentRunId(),state,"EXCLUSIVE",first,last,
          safe(goal.reason()),state.equals("FAILED")||state.equals("UNKNOWN")?safe(goal.reason()):null,
          new TaskView.Progress(goal.attempts(),goal.maxRuns()),references,goal.id(),List.of(),null,null,List.of(),null,
          controlsOwned&&!Set.of("COMPLETED","CANCELLED").contains(goal.status()),resumable,false,goal.attempts()));
    }
    var dependencyLinks=new HashMap<String,List<String>>();
    var parents=new HashMap<String,String>();
    if(dependencies!=null)for(var dependency:sourceRows("dependency:",exact,after,key->dependencies.taskPage(project.id(),project.root().toString(),session,key,limit),key->existing(()->dependencies.get(project.id(),key)))) {
      if(!sameRoot(project,dependency.projectRoot()))continue;
      String id="dependency:"+dependency.id();String parent=referenceTask(project,dependencies.creatorRun(project.id(),dependency.id()));
      if(parent!=null) {
        parents.put(id,parent);dependencyLinks.computeIfAbsent(parent,ignored->new ArrayList<>()).add(dependency.id());
      }
      var history=dependencies.history(project.id(),dependency.id());Instant updated=history.isEmpty()?dependency.createdAt():history.getFirst().timestamp();
      result.put(id,new TaskView(id,"DEPENDENCY",dependency.id(),project.id(),dependency.sessionId(),null,dependency.state().name(),null,
          dependency.createdAt(),updated,safe(dependency.reason()),dependency.state()==dev.mikoto2000.rei.core.dependency.DependencyState.FAILED?safe(dependency.reason()):null,
          null,List.of(new TaskView.Reference("DEPENDENCY",dependency.id())),null,List.of(dependency.id()),null,null,List.of(),null,
          !PersistentDependencyRepository.terminal(dependency.state()),false,false,dependency.version()));
    }
    var scheduleLinks=new HashMap<String,String>();
    if(schedules!=null)for(var schedule:sourceRows("schedule:",exact,after,key->schedules.taskPage(project.id(),project.root().toString(),session,key,limit),key->existing(()->schedules.get(project.id(),key)))) {
      if(!sameRoot(project,schedule.projectRoot()))continue;
      String id="schedule:"+schedule.task().id();String parent=referenceTask(project,schedules.creatorRun(project.id(),schedule.task().id()));
      if(parent!=null)parents.put(id,parent);
      if(schedule.runId()!=null&&runTasks.containsKey(schedule.runId()))scheduleLinks.put(runTasks.get(schedule.runId()),schedule.task().id());
      String state=switch(schedule.status()){case "PENDING","SCHEDULED"->"QUEUED";case "WAITING_EVENT"->"WAITING";default->schedule.status();};
      if(state.equals("RUNNING")&&run(project,schedule.task().conversationId(),schedule.runId())==null)state="UNKNOWN";
      var history=schedules.history(project.id(),schedule.task().id());Instant updated=history.isEmpty()?schedule.task().createdAt():history.getLast().timestamp();
      result.put(id,new TaskView(id,"SCHEDULE",schedule.task().id(),project.id(),schedule.task().conversationId(),schedule.runId(),state,"EXCLUSIVE",
          schedule.task().createdAt(),updated,state.equals("QUEUED")?"due: "+schedule.task().executeAt():state.equals("WAITING")?"event_wait":null,
          state.equals("FAILED")||state.equals("UNKNOWN")?safe(schedule.outcome()):null,null,
          schedule.runId()==null?List.of():List.of(new TaskView.Reference("RUN",schedule.runId())),null,List.of(),schedule.task().id(),null,List.of(),null,
          Set.of("PENDING","SCHEDULED","WAITING_EVENT").contains(schedule.status()),false,false,history.size()));
    }
    parents.putAll(goalParents);
    var children=new HashMap<String,List<String>>();parents.forEach((child,parent)->children.computeIfAbsent(parent,ignored->new ArrayList<>()).add(child));
    return result.values().stream().map(task->{
      String parent=parents.getOrDefault(task.id(),task.parentId());String goal=goalParents.containsKey(task.id())?parent.substring("goal:".length()):task.goalId();
      return related(project,task.links(goal,dependencyLinks.getOrDefault(task.id(),task.dependencyIds()),scheduleLinks.getOrDefault(task.id(),task.schedulerId()),parent,
          children.getOrDefault(task.id(),List.of()).stream().sorted().toList()));
    }).toList();
  }
  private TaskView related(ProjectContext project,TaskView task) {
    var children=new TreeSet<>(task.childIds());var deps=new TreeSet<>(task.dependencyIds());
    if(task.runId()!=null)for(String id:runs.runIds()) {
      var child=run(project,task.sessionId(),id);
      if(child!=null&&child.childOrigin()!=null&&task.id().equals(referenceTask(project,child.childOrigin().parentRunId())))children.add("run:"+id);
    }
    String parent=task.parentId(),goal=task.goalId(),schedule=task.schedulerId();
    if(task.kind().equals("GOAL"))for(var reference:task.results())if(reference.kind().equals("RUN"))children.add(referenceTask(project,reference.id()));
    if(task.kind().equals("RUN")||task.kind().equals("CHECKPOINT")) {
      var lineage=new LinkedHashSet<String>();if(task.runId()!=null)lineage.add(task.runId());lineage.add(task.id().substring(4));
      for(String run:lineage) {
        if(goals!=null) {
          var origin=goals.taskOrigin(project.id(),project.root().toString(),task.sessionId(),run);
          if(origin.isPresent()){goal=origin.get().id();parent="goal:"+goal;}
        }
        if(schedules!=null) {
          var origin=schedules.taskOrigin(project.id(),project.root().toString(),task.sessionId(),run);
          if(origin.isPresent())schedule=origin.get().task().id();
          for(var child:schedules.createdByRun(project.id(),project.root().toString(),task.sessionId(),run))children.add("schedule:"+child.task().id());
        }
        if(dependencies!=null)for(var child:dependencies.createdByRun(project.id(),project.root().toString(),task.sessionId(),run)) {
          deps.add(child.id());children.add("dependency:"+child.id());
        }
      }
    }
    return task.links(goal,List.copyOf(deps),schedule,parent,List.copyOf(children));
  }
  private String referenceTask(ProjectContext project,String run) {
    if(run==null)return null;
    if(checkpoints!=null) {
      var saved=checkpoints.findByRun(project.id(),run).filter(value->sameRoot(project,value.projectRoot()));
      if(saved.isPresent())return "run:"+saved.get().originalRunId();
    }
    return "run:"+run;
  }
  private static String lower(String prefix,String after) {
    if(after==null||after.compareTo(prefix)<0)return "";
    return after.startsWith(prefix)?after.substring(prefix.length()):null;
  }
  private static <T> Optional<T> existing(java.util.function.Supplier<T> get) {
    try{return Optional.of(get.get());}catch(IllegalArgumentException missing){return Optional.empty();}
  }
  private static <T> List<T> sourceRows(String prefix,String exact,String after,
      java.util.function.Function<String,List<T>> page,java.util.function.Function<String,Optional<T>> get) {
    if(exact!=null)return exact.startsWith(prefix)?get.apply(exact.substring(prefix.length())).stream().toList():List.of();
    String key=lower(prefix,after);return key==null?List.of():page.apply(key);
  }
  private String checkpointState(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint saved) {
    return switch(saved.status()){case "RUNNING"->checkpoints.leased(saved.projectId(),saved.taskId())?"RUNNING":"UNKNOWN";
      case "INTERRUPTED"->"SUSPENDED";case "ABANDONED"->"CANCELLED";default->saved.status();};
  }
  private RunSnapshot run(ProjectContext project,String session,String id) {
    if(id==null)return null;
    try {var run=runs.get(id);return project.id().equals(run.context().projectId())&&project.root().equals(run.context().projectRoot())
        &&(session==null||session.equals(session(run)))?run:null;}catch(RunNotFoundException missing){return null;}
  }
  private static String session(RunSnapshot run) {
    String session=run.childOrigin()==null?run.context().conversationId():run.childOrigin().sessionId();
    return session.isEmpty()?null:session;
  }
  private static boolean sameRoot(ProjectContext project,String root) {
    try{return project.root().equals(Path.of(root).toAbsolutePath().normalize());}catch(RuntimeException invalid){return false;}
  }
  private static String safe(String value) {
    if(value==null)return null;String text=dev.mikoto2000.rei.event.CredentialRedactor.redact(value);return text.substring(0,Math.min(1024,text.length()));
  }
}
