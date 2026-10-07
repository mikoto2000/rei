package dev.mikoto2000.rei.planning;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.task.*;
import dev.mikoto2000.rei.workcontext.*;
import dev.mikoto2000.rei.goal.GoalRepository;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.activity.ActivityWorkContextService;
import dev.mikoto2000.rei.event.CredentialRedactor;
/** Deterministic read projection. No extraction, scheduling, reconciliation, or model invocation. */
public final class DailyPlanningService {
  public static final List<String> CATEGORIES=List.of("Today","Overdue","Waiting","Blocked","Scheduled","Suggested Next");
  public record Plan(int schemaVersion,LocalDate date,String zone,Instant generatedAt,String method,List<String> categories,List<ProjectPlan> projects,boolean partial) {
    public Plan{categories=List.copyOf(categories);projects=List.copyOf(projects);}
  }
  public record ProjectPlan(String projectId,String name,List<Item> items,ActivitySummary activity,boolean partial,List<String> warnings) {
    public ProjectPlan{items=List.copyOf(items);warnings=List.copyOf(warnings);}
  }
  public record Item(String id,String source,String title,String reason,String status,String sessionId,Instant updatedAt,Instant dueAt,
      List<String> categories,List<String> tags,String certainty,List<TaskView.Reference> references) {
    public Item{categories=List.copyOf(categories);tags=List.copyOf(tags);references=List.copyOf(references);}
  }
  public record Observation(String recordId,Instant capturedAt,List<String> itemIds,String basis) {public Observation{itemIds=List.copyOf(itemIds);}}
  public record ActivitySummary(LocalDate date,String zone,String provenance,int linkedObservations,boolean partial,int missingContextRecords,List<Observation> references) {
    public ActivitySummary{references=List.copyOf(references);}
  }
  private final Clock clock;private final ZoneId zone;private final int taskLimit,itemLimit,budgetSeconds;private final Duration staleAfter;
  private final ProjectRegistry projects;private final List<ProjectContext> allowed;private final TaskManagerService tasks;
  private final WorkContextRepository contexts;private final GoalRepository goals;private final PersistentDependencyRepository dependencies;
  private final PersistentAgentScheduler schedules;private final PersistentCheckpointRepository checkpoints;private final ActivityWorkContextService activity;
  public DailyPlanningService(DailyPlanningProperties properties,Clock clock,ProjectRegistry projects,TaskManagerService tasks,
      WorkContextRepository contexts,GoalRepository goals,PersistentDependencyRepository dependencies,PersistentAgentScheduler schedules,
      PersistentCheckpointRepository checkpoints,ActivityWorkContextService activity) {
    properties.validate();this.clock=clock;this.zone=ZoneId.of(properties.getZone());this.projects=projects;this.tasks=tasks;this.contexts=contexts;
    this.goals=goals;this.dependencies=dependencies;this.schedules=schedules;this.checkpoints=checkpoints;this.activity=activity;
    taskLimit=properties.getMaxTasksPerProject();itemLimit=properties.getMaxItemsPerProject();budgetSeconds=properties.getCollectionBudgetSeconds();staleAfter=properties.getStaleAfter();
    allowed=properties.getProjects().stream().sorted().map(id->projects.resolveById(id).orElseThrow(()->new IllegalArgumentException("Today Project not registered"))).toList();
  }
  public Plan today(List<String> requested) {
    var selected=requested==null?allowed:selection(requested);
    for(var owner:selected)verifyRoot(owner);
    Instant now=clock.instant();LocalDate date=now.atZone(zone).toLocalDate();Instant start=date.atStartOfDay(zone).toInstant(),end=date.plusDays(1).atStartOfDay(zone).toInstant();
    long deadline=System.nanoTime()+Duration.ofSeconds(budgetSeconds).toNanos();var results=new ArrayList<ProjectPlan>();
    for(var owner:selected) {
      var warnings=new LinkedHashSet<String>();var items=new ArrayList<Item>();
      int scanned=0;String cursor=null;
      do {
        if(expired(deadline)){warnings.add("collection_budget_reached");break;}
        var page=tasks.list(owner.id(),null,Math.min(100,taskLimit-scanned),cursor);
        for(var task:page.items()) {
          scanned++;if(Set.of("COMPLETED","CANCELLED").contains(task.status()))continue;
          if(items.size()==itemLimit){warnings.add("item_limit_reached");break;}
          var item=task(owner,task,now,start,end);if(item!=null){items.add(item);if(item.tags().contains("REFERENCES_PARTIAL"))warnings.add("task_references_partial");}
        }
        cursor=page.nextCursor();
        if(scanned==taskLimit&&cursor!=null){warnings.add("task_limit_reached");break;}
        if(warnings.contains("item_limit_reached"))break;
      }while(cursor!=null);
      if(contexts==null)warnings.add("work_context_unavailable");
      else if(expired(deadline))warnings.add("collection_budget_reached");
      else contexts.current(owner.id()).ifPresent(saved->{
        if(!owner.id().equals(saved.projectId())||saved.git()==null||!sameRoot(owner,saved.git().directory())){warnings.add("work_context_root_unverified");return;}
        for(var item:saved.items()) {
          if(Set.of(WorkContext.Status.COMPLETED,WorkContext.Status.WITHDRAWN,WorkContext.Status.SUPERSEDED).contains(item.status())
              ||!Set.of(WorkContext.Kind.PURPOSE,WorkContext.Kind.CURRENT_WORK,WorkContext.Kind.PENDING,WorkContext.Kind.BLOCKER,WorkContext.Kind.NEXT_ACTION,WorkContext.Kind.VERIFICATION).contains(item.kind()))continue;
          if(items.size()==itemLimit){warnings.add("item_limit_reached");break;}
          var categories=new LinkedHashSet<String>();var tags=new ArrayList<String>();tags.add(item.kind().name());
          if(item.kind()==WorkContext.Kind.BLOCKER)categories.add("Blocked");
          else if(item.kind()==WorkContext.Kind.NEXT_ACTION||item.kind()==WorkContext.Kind.VERIFICATION)categories.add("Suggested Next");
          else categories.add("Today");
          if(item.updatedAt()!=null&&item.updatedAt().isBefore(now.minus(staleAfter)))tags.add("STALE");
          items.add(new Item("work-context:"+item.id(),"WORK_CONTEXT",safe(item.text(),512),safe(item.reason(),512),item.status().name(),null,item.updatedAt(),null,List.copyOf(categories),tags,item.certainty().name(),List.of(new TaskView.Reference("WORK_CONTEXT_REVISION",Long.toString(saved.revision())))));
        }
      });
      ActivitySummary observation=null;
      if(activity!=null&&!expired(deadline)) {
        try {
          var saved=activity.observationLinksBounded(owner.id(),"today");
          var refs=saved.links().stream().map(link->new Observation(safe(link.recordId(),128),link.capturedAt(),link.itemIds().stream().limit(20).map(id->safe(id,128)).toList(),"OBSERVATION_CONTEXT")).toList();
          observation=new ActivitySummary(saved.date(),saved.zone(),"OBSERVATION_ONLY",refs.size(),saved.partial(),saved.missingContextRecords(),refs);
          if(saved.partial()||saved.missingContextRecords()>0)warnings.add("activity_evidence_partial");
        }catch(dev.mikoto2000.rei.activity.ActivityQueryLimitException|UnsupportedOperationException unavailable){warnings.add("activity_evidence_unavailable");}
      }else if(activity!=null)warnings.add("collection_budget_reached");
      verifyRoot(owner);
      items.sort(Comparator.comparing((Item item)->item.dueAt()==null?Instant.MAX:item.dueAt()).thenComparing(Item::id));
      results.add(new ProjectPlan(owner.id(),safe(owner.name(),128),items,observation,warnings.stream().anyMatch(this::partial),List.copyOf(warnings)));
    }
    return new Plan(1,date,zone.getId(),now,"DETERMINISTIC",CATEGORIES,results,results.stream().anyMatch(ProjectPlan::partial));
  }
  private boolean partial(String warning){return Set.of("collection_budget_reached","task_limit_reached","item_limit_reached","task_references_partial","work_context_root_unverified","activity_evidence_partial","activity_evidence_unavailable").contains(warning);}
  private Item task(ProjectContext owner,TaskView task,Instant now,Instant start,Instant end) {
    String title=task.kind()+" "+task.sourceId();Instant due=null;var tags=new ArrayList<String>();var refs=new ArrayList<>(task.results());
    if(task.kind().equals("GOAL")&&goals!=null){var saved=goals.get(owner.id(),task.sourceId());if(!sameRoot(owner,saved.projectRoot()))return null;title=saved.objective();tags.add("CURRENT_GOAL");}
    if(task.kind().equals("DEPENDENCY")&&dependencies!=null){var saved=dependencies.get(owner.id(),task.sourceId());if(!sameRoot(owner,saved.projectRoot()))return null;title=saved.spec().kind()+": "+saved.spec().target();due=saved.deadline();}
    if(task.kind().equals("SCHEDULE")&&schedules!=null){var saved=schedules.get(owner.id(),task.sourceId());if(!sameRoot(owner,saved.projectRoot()))return null;title=saved.task().action();due=task.status().equals("WAITING")?schedules.eventTrigger(owner.id(),task.sourceId()).map(PersistentAgentScheduler.EventTrigger::expiresAt).orElse(null):saved.task().executeAt();tags.add("SAVED_CONTINUATION");if(saved.status().equals("PENDING"))tags.add("ACTIVATION_REQUIRED");}
    if(task.kind().equals("CHECKPOINT")&&checkpoints!=null){var saved=checkpoints.get(owner.id(),task.sourceId());if(!sameRoot(owner,saved.projectRoot()))return null;title=saved.nextAction()==null?saved.request():saved.nextAction();if(saved.resumedFromRunId()!=null)tags.add("RESUMED");}
    if(task.updatedAt()!=null&&task.updatedAt().isBefore(now.minus(staleAfter)))tags.add("STALE");
    var categories=new LinkedHashSet<String>();
    if(task.status().equals("WAITING"))categories.add("Waiting");
    if(Set.of("BLOCKED","FAILED","UNKNOWN").contains(task.status()))categories.add("Blocked");
    if(task.kind().equals("SCHEDULE"))categories.add("Scheduled");
    if(due!=null&&due.isBefore(now))categories.add("Overdue");
    if(due!=null&&!due.isBefore(start)&&due.isBefore(end)||categories.isEmpty())categories.add("Today");
    if(refs.size()>32)tags.add("REFERENCES_PARTIAL");
    return new Item(task.id(),task.kind(),safe(title,512),safe(Objects.toString(task.waitingReason(),"")+" "+Objects.toString(task.errorSummary(),""),512).strip(),task.status(),task.sessionId(),task.updatedAt(),due,List.copyOf(categories),tags,"SAVED_EXECUTION_STATE",refs.stream().limit(32).toList());
  }
  private List<ProjectContext> selection(List<String> requested){
    if(requested.isEmpty()||requested.size()>16||requested.stream().anyMatch(Objects::isNull)||new HashSet<>(requested).size()!=requested.size())throw new IllegalArgumentException("Invalid Today selection");
    return requested.stream().sorted().map(id->allowed.stream().filter(project->project.id().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("Today Project"))).toList();
  }
  private void verifyRoot(ProjectContext owner){if(projects.resolveById(owner.id()).filter(current->current.root().equals(owner.root())).isEmpty())throw new ResourceNotFoundException("Today Project");}
  private static boolean sameRoot(ProjectContext owner,String root){try{return root!=null&&owner.root().equals(Path.of(root).toAbsolutePath().normalize());}catch(RuntimeException invalid){return false;}}
  private static boolean expired(long deadline){return System.nanoTime()-deadline>=0;}
  private static String safe(String value,int limit){var text=CredentialRedactor.redact(Objects.toString(value,""));return text.substring(0,Math.min(limit,text.length()));}
}
