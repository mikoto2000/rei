package dev.mikoto2000.rei.goal;

import java.nio.file.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
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
    var reservation=new OutputLimitRunBudget.LlmCallReservation() {
      public boolean tryReserve(){return goals.reserveLlm(claim);}
      public int remaining(){return goals.remainingLlm(claim);}
    };
    router.submitOperation(owner,()->{
      GoalLoopService.Outcome outcome;
      try {
        if(!goals.active(claim)) {cancellation.forgetPendingCancellation(run);outcome=new GoalLoopService.Outcome(ChatExecutionResult.cancelled());}
        else {
          validate(goal);
          if(verifier.verify(goal).satisfied())outcome=new GoalLoopService.Outcome(ChatExecutionResult.success("Criterion already satisfied",false));
          else {
            String prompt="Goal: "+goal.objective()+"\nCompletion criterion: Project-relative file "+goal.relativeFile()+" must have SHA-256 "+goal.sha256()
                +". Use the existing action plan and task state to choose and execute the next bounded step. "
                +"The host verifies the file independently; a completion statement is insufficient. "
                +"Previous attempts remain in this conversation. Do not repeat an already completed side effect.";
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
      completed.accept(outcome);
    },work->{try {work.run();}catch(RuntimeException error){completed.accept(new GoalLoopService.Outcome(ChatExecutionResult.failed("Goal admission failed")));}});
  }
  @Override public void cancel(GoalRepository.Goal goal) {
    if(goal.currentRunId()==null)return;
    if(router.cancelQueued(goal.currentRunId())) {
      cancellation.forgetPendingCancellation(goal.currentRunId());
      // Queued operations never enter ChatExecutionService; the durable Goal is already CANCELLED.
    } else cancellation.cancelRegisteredRun(goal.currentRunId());
  }
}
