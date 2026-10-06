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
  private final GoalRepository goals;
  private final FileGoalVerifier verifier;
  private final ProjectService projects;
  private final SessionRepository sessions;
  private final ConversationInputRouter router;
  private final ChatExecutionService chat;
  private final CommandCancellationService cancellation;
  private final AgentEventFactory events;
  private final AgentEventPublisher publisher;
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
    var goal=claim.goal();validate(goal);
    var owner=new AgentRunContext(run,goal.sessionId(),Path.of(goal.projectRoot()),goal.projectId(),AgentRunContext.RequestSource.WEB);
    var reservation=goals.modelBudget(claim,run);
    boolean registered=false;
    try {if(runRegistry!=null) {
      runRegistry.register(owner);
      registered=true;
      runLifecycle.onQueuedCancellation(run,()->completed.accept(new GoalLoopService.Outcome(ChatExecutionResult.cancelled())));
    }
    router.submitOperation(owner,()->{
      GoalLoopService.Outcome outcome;
      try {
        if(!goals.active(claim)) {cancellation.forgetPendingCancellation(run);outcome=new GoalLoopService.Outcome(ChatExecutionResult.cancelled());}
        else {
          validate(goal);
          if(verifier.verify(goal).satisfied())outcome=new GoalLoopService.Outcome(ChatExecutionResult.success("Criterion already satisfied",false));
          else {
            boolean uncertain=goals.attempts(goal.projectId(),goal.id()).stream().anyMatch(a->a.reason().equals("uncertain_run_reconciled"));
            String prompt="Goal: "+goal.objective()+"\nCompletion criteria: ALL Project-relative file predicates must match. SHA-256 predicates require the exact digest; JSON predicates require the fixed JSON Pointer's scalar to equal expectedJson with its JSON type: "+goal.criteria()
                +". Use the existing action plan and task state to choose and execute the next bounded step. "
                +"The host verifies the file independently; a completion statement is insufficient. "
                +"Previous attempts remain in this conversation. Do not repeat an already completed side effect."
                +(uncertain?" An earlier Run has unknown side effects. Inspect current artifacts and saved history before deciding whether any action needs to be repeated.":"");
            outcome=new GoalLoopService.Outcome(chat.execute(owner,prompt,new UserInterventionQueue(),reservation));
          }
        }
      } catch(RuntimeException error) {
        String reason="execution_failed";
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()) {
          if(cause instanceof ToolPermissionException permission){reason=permission.decision()==PermissionDecision.REQUIRE_APPROVAL?"permission_required":"policy_denied";break;}
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
