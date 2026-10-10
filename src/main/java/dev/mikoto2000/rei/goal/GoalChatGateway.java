package dev.mikoto2000.rei.goal;

import java.nio.file.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.event.*;

/** Reuses the Project FIFO and Chat boundaries, capturing ownership before queue admission. */
@Component
public class GoalChatGateway implements GoalLoopService.Gateway {
  private dev.mikoto2000.rei.timing.TimingExecution timing;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void setTiming(dev.mikoto2000.rei.timing.TimingExecution timing){this.timing=timing;}
  private final GoalRepository goals;
  private final FileGoalVerifier verifier;
  private final ProjectService projects;
  private final SessionRepository sessions;
  private final ConversationInputRouter router;
  private final ChatExecutionService chat;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
  private int repairReserveCalls=2;
  @org.springframework.beans.factory.annotation.Value("${rei.goal.repair-reserve-calls:2}")
  public void configureRepairReserve(int calls) {
    if(calls<0||calls>10)throw new IllegalArgumentException("Repair reserve must be 0..10 calls");
    repairReserveCalls=calls;
  }
  private dev.mikoto2000.rei.application.run.RunRegistry runRegistry;
  private dev.mikoto2000.rei.application.run.RunService runLifecycle;
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  public void configureRunTracking(dev.mikoto2000.rei.application.run.RunRegistry registry,dev.mikoto2000.rei.application.run.RunService lifecycle) {
    this.runRegistry=registry;this.runLifecycle=lifecycle;
  }
  public GoalChatGateway(GoalRepository goals,FileGoalVerifier verifier,ProjectService projects,SessionRepository sessions,
      ConversationInputRouter router,ChatExecutionService chat,CommandCancellationService cancellation,AgentEventFactory events,AgentEventPublisher publisher) {
    this.goals=goals;this.verifier=verifier;this.projects=projects;this.sessions=sessions;this.router=router;this.chat=chat;
    this.cancellation=cancellation;this.events=events;this.publisher=publisher;
  }
  @Override public void validate(GoalRepository.Goal goal) {
    var root=Path.of(goal.projectRoot());
    try {
      if(!Files.isDirectory(root)||!root.toRealPath().equals(root)||projects.registeredProjects().stream().noneMatch(p->p.id().equals(goal.projectId())&&p.root().equals(root)))
        throw new IllegalStateException("Goal Project is missing or relocated");
    } catch(java.io.IOException error){throw new IllegalStateException("Goal Project is unavailable");}
    var session=sessions.findById(goal.sessionId()).orElseThrow(()->new IllegalStateException("Goal Session is missing"));
    if(!session.projectId().equals(goal.projectId()))throw new IllegalStateException("Goal Session belongs to another Project");
  }
  @Override public void dispatch(GoalRepository.Claim claim,String run,Consumer<GoalLoopService.Outcome> completed) {
    var goal=goals.get(claim.goal().projectId(),claim.goal().id());validate(goal);
    var owner=new AgentRunContext(run,goal.sessionId(),Path.of(goal.projectRoot()),goal.projectId(),AgentRunContext.RequestSource.WEB);
    var parentBudget=goals.modelBudget(claim,run);
    boolean repairing=goals.completionPhase(goal.projectId(),goal.id()).equals("REPAIRING");
    GoalRepairBudget slice=goal.completion()!=null&&!repairing&&goal.attempts()<goal.maxRuns()
        ?new GoalRepairBudget(parentBudget,repairReserveCalls):null;
    dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation=slice==null?parentBudget:slice;
    boolean registered=false;
    try {if(runRegistry!=null) {
      runRegistry.register(owner);
      registered=true;
      runLifecycle.onQueuedCancellation(run,()->completed.accept(new GoalLoopService.Outcome(ChatExecutionResult.cancelled())));
    }
    router.submitOperation(owner,()->{
      java.util.function.Supplier<ChatExecutionResult> operation=()->{
      GoalLoopService.Outcome outcome;
      try {
        if(!goals.active(claim)) {cancellation.forgetPendingCancellation(run);outcome=new GoalLoopService.Outcome(ChatExecutionResult.cancelled());}
        else {
          validate(goal);
          if(verifier.verify(goal).satisfied())outcome=new GoalLoopService.Outcome(ChatExecutionResult.success("Criterion already satisfied",false));
          else {
            boolean uncertain=goals.attempts(goal.projectId(),goal.id()).stream().anyMatch(a->a.reason().equals("uncertain_run_reconciled"));
            String prompt="Goal: "+goal.objective()+"\nGoal ID: "+goal.id()+"\nCompletion definition: "+goal.completion()+"\nCompletion criteria: ALL Project-relative file predicates must match. SHA-256 predicates require the exact digest; JSON predicates require the fixed JSON Pointer's scalar to equal expectedJson with its JSON type: "+goal.criteria()
                +". Use the existing action plan and task state to choose and execute the next bounded step. "
                +"The host verifies the file independently; a completion statement is insufficient. "
                +(goal.completion()==null?"":"For required review/tests, use reviewPatchRequirements with the human-defined requirements and exact test command. Attach saved Review/Artifact IDs and SHAs with attachGoalCompletionEvidence for this Goal ID. Never weaken the definition or invent evidence. ")
                +"Previous attempts remain in this conversation. Do not repeat an already completed side effect."
                +(uncertain?" An earlier Run has unknown side effects. Inspect current artifacts and saved history before deciding whether any action needs to be repeated.":"");
            var previous=goals.attempts(goal.projectId(),goal.id()).stream()
                .filter(a->!a.runId().equals(run)).reduce((a,b)->b).orElse(null);
            if(previous!=null&&previous.status().equals("UNVERIFIED")) {
              var current=GoalCompletionProgress.inspect(goal,"REPAIRING",verifier);
              prompt+="\nRepair diagnosis: "+GoalRepairDiagnosis.of(previous.reason()).kind()+" ("+previous.reason()+")"
                  +"\nCurrent unmet required conditions: "+current.conditions().stream().filter(c->c.required()&&!c.satisfied()).toList()
                  +"\nCurrent independently verified state: "+current.verification()
                  +"\nRepair only unmet conditions using the existing planner, task state, test diagnosis and review tools. "
                  +"Inspect current files and saved effects before mutations. Keep human requirements and exact test command fixed. "
                  +"Do not repeat an effect with an unknown outcome or apply a human-only repair without permission.";
            }
            var result=chat.execute(owner,prompt,new UserInterventionQueue(),reservation);
            String stop=slice!=null&&slice.boundaryReached()&&result.stopCode().equals("llm_call_budget_exceeded")
                ?"repair_reserve_reached":result.stopCode();
            outcome=new GoalLoopService.Outcome(result,stop);
          }
        }
      } catch(RuntimeException error) {
        String reason="execution_failed";
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()) {
          if(cause instanceof ToolPermissionException permission){reason=permission.decision()==PermissionDecision.REQUIRE_APPROVAL?"permission_required":"policy_denied";break;}
          if(cause instanceof dev.mikoto2000.rei.http.HttpFetchException http&&java.util.Set.of(
              dev.mikoto2000.rei.http.HttpFetchException.Code.CONNECT_TIMEOUT,dev.mikoto2000.rei.http.HttpFetchException.Code.READ_TIMEOUT,
              dev.mikoto2000.rei.http.HttpFetchException.Code.TOTAL_TIMEOUT,dev.mikoto2000.rei.http.HttpFetchException.Code.NETWORK_ERROR).contains(http.code()))reason="external_transient";
          if(cause==cause.getCause())break;
        }
        publisher.publish(events.runFailed(run,new ErrorInformation(error.getClass().getSimpleName(),"Goal Run failed",null)).withOwnership(owner));
        outcome=new GoalLoopService.Outcome(ChatExecutionResult.failed("Goal Run failed"),reason);
      }
      if(runLifecycle!=null) {
        var terminal=outcome.result().status()==ChatExecutionResult.Status.CANCELLED?dev.mikoto2000.rei.application.run.RunStatus.CANCELLED
            :outcome.result().success()?dev.mikoto2000.rei.application.run.RunStatus.COMPLETED:dev.mikoto2000.rei.application.run.RunStatus.FAILED;
        runLifecycle.finishMissingTerminal(owner,terminal);
        if(runRegistry.get(run).status()==dev.mikoto2000.rei.application.run.RunStatus.CANCELLED)outcome=new GoalLoopService.Outcome(ChatExecutionResult.cancelled());
      }
      completed.accept(outcome);
      return outcome.result();
      };
      if(timing==null)operation.get();else timing.observeRun(owner,operation);
    },work->{
      Runnable execute=()->{try {work.run();}catch(RuntimeException error){completed.accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("Goal admission failed")));}};
      if(runLifecycle!=null)runLifecycle.execute(owner,execute);else execute.run();
    });}catch(RuntimeException|Error error){if(registered){runLifecycle.forgetQueuedCancellation(run);runRegistry.forget(run);}throw error;}
  }
  @Override public boolean isInFlight(GoalRepository.Goal goal) {
    return goal.currentRunId()!=null&&router.containsRun(goal.projectId(),goal.currentRunId());
  }
  @Override public void cancel(GoalRepository.Goal goal) {
    if(goal.currentRunId()==null)return;
    if(runLifecycle!=null)try {runLifecycle.cancel(goal.currentRunId());return;}
    catch(dev.mikoto2000.rei.application.run.RunNotFoundException missing) { /* Restored/expired Run has no live registry entry. */ }
    if(router.cancelQueued(goal.currentRunId())) {
      cancellation.forgetPendingCancellation(goal.currentRunId());
      // Queued operations never enter ChatExecutionService; the durable Goal is already CANCELLED.
    } else cancellation.cancelRegisteredRun(goal.currentRunId());
  }
}
