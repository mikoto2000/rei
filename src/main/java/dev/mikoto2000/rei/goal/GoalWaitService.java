package dev.mikoto2000.rei.goal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.checkpoint.*;
import dev.mikoto2000.rei.temporal.*;
import dev.mikoto2000.rei.event.*;
/** Human opt-in binding; the existing dispatcher and Goal loop retain execution authority. */
@Service
public class GoalWaitService {
 private final GoalWaitRepository waits;private final GoalRepository goals;private final GoalLoopService loop;
 private final PersistentDependencyRepository dependencies;private final DependencyObservationService observations;
 private final PersistentAgentScheduler schedules;private final PersistentCheckpointService checkpoints;
 private final PersistentCheckpointRepository checkpointRepository;private final Clock clock;private final GoalEvents events;
 public GoalWaitService(GoalWaitRepository waits,GoalRepository goals,GoalLoopService loop,PersistentDependencyRepository dependencies,
     DependencyObservationService observations,PersistentAgentScheduler schedules,PersistentCheckpointService checkpoints,
     PersistentCheckpointRepository checkpointRepository,Clock clock,GoalEvents events){
  this.waits=waits;this.goals=goals;this.loop=loop;this.dependencies=dependencies;this.observations=observations;
  this.schedules=schedules;this.checkpoints=checkpoints;this.checkpointRepository=checkpointRepository;this.clock=clock;this.events=events;
 }
 public GoalWaitRepository.Wait show(String project,String goal){goals.get(project,goal);return waits.get(project,goal);}
 public GoalWaitRepository.Wait waitFor(String project,String id,String dependency,String reason){
  if(reason==null||!reason.matches("[a-z_]{1,80}"))throw new IllegalArgumentException("Structured wait reason required");
  var goal=goals.get(project,id);var progress=loop.progress(project,id);
  if(!Set.of("READY","PAUSED","FAILED","WAITING_APPROVAL","BLOCKED").contains(goal.status()))throw new IllegalStateException("Stop or reconcile the owning Goal Run first");
  var entry=dependencies.get(project,dependency);
  if(!goal.projectRoot().equals(entry.projectRoot())||!goal.sessionId().equals(entry.sessionId()))throw new IllegalArgumentException("Dependency outside Goal ownership");
  var remaining=Duration.between(clock.instant(),entry.deadline());
  if(remaining.compareTo(Duration.ofSeconds(1))<0||entry.state()==DependencyState.CANCELLED||entry.state()==DependencyState.FAILED)throw new IllegalStateException("Dependency is unavailable or expired");
  PersistentCheckpoint saved=null;
  if(goal.currentRunId()!=null){
   saved=checkpointRepository.findByRun(project,goal.currentRunId()).orElseThrow(()->new IllegalStateException("Owning Run checkpoint required; inspect effects before continuation"));
   validateCheckpoint(goal,saved);
  }
  var snapshot=new GoalWaitRepository.Snapshot(progress,goal.completion(),entry.spec(),saved==null?null:saved.taskId(),saved==null?0:saved.revision(),
      saved==null?List.of():saved.operations(),saved==null?goal.objective():saved.nextAction());
  var owner=new AgentRunContext(UUID.randomUUID().toString(),goal.sessionId(),Path.of(goal.projectRoot()),project);
  var wait=waits.create(goal,dependency,reason,snapshot,()->{
   try(var scope=AgentRunScope.open(owner)){return schedules.scheduleOnEvent(dependency,AgentEventType.DEPENDENCY_COMPLETED,remaining,"Resume verified Goal "+id,goal.sessionId()).id();}
  });
  events.publish(goals.get(project,id),"WAITING");return wait;
 }
 private void validateCheckpoint(GoalRepository.Goal goal,PersistentCheckpoint state){
  if(!state.runId().equals(goal.currentRunId())||!state.projectId().equals(goal.projectId())||!state.projectRoot().equals(goal.projectRoot())||!state.sessionId().equals(goal.sessionId())||state.mode()!=AgentRunContext.Mode.EXCLUSIVE)
   throw new IllegalStateException("Checkpoint does not match the stopped Goal Run");
 }
 public GoalWaitRepository.Wait activate(String project,String id,long version){
  var wait=expected(project,id,version);requireWaiting(wait);loop.progress(project,id);
  schedules.activate(project,wait.scheduleId());
  var fresh=observations.recheckForResume(project,wait.dependencyId());
  // A condition that was already met at activation needs a current observation, not an old event.
  if(fresh.state()==DependencyState.COMPLETED){
   var dependency=dependencies.get(project,wait.dependencyId());
   schedules.signalExternalEvent(new dev.mikoto2000.rei.event.AgentEvent(UUID.randomUUID().toString(),0,clock.instant(),AgentEventType.DEPENDENCY_COMPLETED,1,
       dependency.sessionId(),null,null,dependency.id(),null,new DependencyStatusPayload(dependency.id(),dependency.spec().kind().name(),"COMPLETED",fresh.detail(),dependency.version()),project),Path.of(dependency.projectRoot()));
  }
  return waits.transition(wait,"WAITING",fresh.detail(),null);
 }
 private GoalWaitRepository.Wait expected(String project,String id,long version){
  var wait=show(project,id);if(wait.version()!=version)throw new IllegalStateException("Goal wait revision changed");return wait;
 }
 private void requireWaiting(GoalWaitRepository.Wait wait){if(!Set.of("WAITING","BLOCKED").contains(wait.state()))throw new IllegalStateException("Wait is already resumed, cancelled, or its admission is uncertain");}
 public GoalWaitRepository.Wait resume(String project,String id,long version){return resume(expected(project,id,version),null);}
 private GoalWaitRepository.Wait resume(GoalWaitRepository.Wait wait,PersistentAgentScheduler.Entry scheduled){
  requireWaiting(wait);
  var goal=goals.get(wait.project(),wait.goalId());loop.progress(wait.project(),wait.goalId());
  String blocked=null;
  if(!goal.status().equals("PAUSED")||!Objects.equals(goal.currentRunId(),wait.runId())||goal.attempts()!=wait.attempts()||!Objects.equals(goal.completion(),wait.snapshot().completion()))blocked="goal_changed";
  var dependency=dependencies.get(wait.project(),wait.dependencyId());
  if(!goal.projectRoot().equals(dependency.projectRoot())||!goal.sessionId().equals(dependency.sessionId()))blocked="owner_unavailable";
  var timer=schedules.get(wait.project(),wait.scheduleId());
  if(!timer.projectRoot().equals(goal.projectRoot())||!timer.task().conversationId().equals(goal.sessionId()))blocked="owner_unavailable";
  if(scheduled==null&&timer.status().equals("RUNNING"))throw new IllegalStateException("Wait is owned by Scheduler");
  if(scheduled!=null&&(!timer.runId().equals(scheduled.runId())||!schedules.activeClaim(scheduled)))throw new IllegalStateException("Schedule claim inactive");
  if(goal.attempts()>=goal.maxRuns()||goal.llmCallsUsed()>=goal.maxLlmCalls()||goal.maxTotalTokens()>0&&(goal.tokenUsageUnknown()||goal.pendingLlmCalls()>0||goal.totalTokens()>=goal.maxTotalTokens()))blocked="budget_exhausted";
  var fresh=observations.recheckForResume(wait.project(),wait.dependencyId());
  if(fresh.state()!=DependencyState.COMPLETED)blocked=fresh.detail();
  PersistentCheckpoint saved=null;
  if(wait.runId()!=null){
   try{
    saved=checkpoints.get(wait.project(),wait.snapshot().checkpointTask());validateCheckpoint(goal,saved);
    var reconciliation=checkpoints.inspect(wait.project(),saved.taskId());
    if(!reconciliation.automaticResumeSafe())blocked="checkpoint_reconciliation_required";
   }catch(RuntimeException unavailable){RunCancellation.propagate(unavailable);blocked="checkpoint_reconciliation_required";}
  }
  if(blocked!=null){waits.transition(wait,"BLOCKED",blocked,null);throw new IllegalStateException("Goal resume blocked: "+blocked);}
  var claimed=waits.transition(wait,"RESUMING",fresh.detail(),null);
  var checkpoint=saved;
  try{
   if(scheduled==null&&!Set.of("COMPLETED","FAILED","CANCELLED").contains(timer.status()))schedules.cancel(wait.project(),wait.scheduleId());
   var resumed=loop.resumeWaiting(goal,claimed,owner->{if(checkpoint!=null)checkpoints.prepareGoalContinuation(owner,checkpoint.taskId(),wait.runId(),checkpoint.revision());});
   return waits.transition(claimed,"RESUMED","goal_resume_admitted",resumed.currentRunId());
  }catch(RuntimeException error){
   var current=goals.get(wait.project(),wait.goalId());
   if(current.currentRunId()!=null)checkpoints.abortPreparedGoalContinuation(current.currentRunId());
   waits.transition(claimed,"RESUMING","resume_admission_unknown",null);RunCancellation.propagate(error);throw error;
  }
 }
 public boolean handles(PersistentAgentScheduler.Entry entry){return waits.bySchedule(entry.projectId(),entry.task().id()).isPresent();}
 public void dispatch(PersistentAgentScheduler.Entry entry,Consumer<ChatExecutionResult> done){
  var wait=waits.bySchedule(entry.projectId(),entry.task().id()).orElseThrow();
  try{resume(wait,entry);done.accept(ChatExecutionResult.success("Goal continuation admitted; inspect Goal completion separately",false));}
  catch(RuntimeException error){RunCancellation.propagate(error);done.accept(ChatExecutionResult.failed("Goal continuation was not admitted"));}
 }
 public GoalWaitRepository.Wait cancel(String project,String id,long version){
  var wait=expected(project,id,version);
  var goal=goals.get(project,id);
  if(goal.status().equals("RUNNING"))throw new IllegalStateException("Stop or reconcile the Goal Run before releasing a wait");
  if(!Set.of("WAITING","BLOCKED","RESUMING").contains(wait.state()))throw new IllegalStateException("Wait is terminal");
  var timer=schedules.get(project,wait.scheduleId());
  if(timer.status().equals("RUNNING"))throw new IllegalStateException("Reconcile the exact Scheduler Run before releasing a wait");
  if(!Set.of("COMPLETED","FAILED","CANCELLED").contains(timer.status()))schedules.cancel(project,wait.scheduleId());
  return waits.transition(wait,"CANCELLED","human_wait_cancelled",null);
 }
}