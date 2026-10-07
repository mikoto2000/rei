package dev.mikoto2000.rei.application.task;

import java.util.Objects;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointService;
import dev.mikoto2000.rei.goal.GoalLoopService;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;

/** Human controls delegate to existing source owners and never run a second execution loop. */
public final class TaskControlService {
  private final TaskManagerService tasks;
  private final ChatSubmitService chats;
  private final RunService runs;
  private final RunRegistry registry;
  private final ConversationInputRouter inputs;
  private final PersistentCheckpointService checkpoints;
  private final GoalLoopService goals;
  private final PersistentDependencyRepository dependencies;
  private final PersistentAgentScheduler schedules;
  public TaskControlService(TaskManagerService tasks,ChatSubmitService chats,RunService runs,RunRegistry registry,
      ConversationInputRouter inputs,PersistentCheckpointService checkpoints,GoalLoopService goals,
      PersistentDependencyRepository dependencies,PersistentAgentScheduler schedules) {
    this.tasks=tasks;this.chats=chats;this.runs=runs;this.registry=registry;this.inputs=inputs;
    this.checkpoints=checkpoints;this.goals=goals;this.dependencies=dependencies;this.schedules=schedules;
  }
  public TaskView submit(String project,String session,String message,AgentRunContext.Mode mode) {
    if(message==null||message.isBlank()||message.length()>16384)throw new IllegalArgumentException("Invalid Task input");
    var run=chats.submit(message,project,session,mode);
    return tasks.get(run.projectId(),run.conversationId(),"run:"+run.runId());
  }
  public TaskView cancel(String project,String session,String id,String expectedRun,long revision) {
    if(revision<0)throw new IllegalArgumentException("Invalid Task revision");
    var task=tasks.get(project,session,id);
    if(task.status().equals("CANCELLED")&&Objects.equals(expectedRun,task.runId()))return task;
    expected(task,expectedRun,revision);
    if(!task.cancelSupported())throw new OperationConflictException();
    switch(task.kind()) {
      case "RUN","CHECKPOINT","SUBAGENT"->{owned(task.runId());runs.cancel(task.runId());}
      case "GOAL"->{if(task.status().equals("RUNNING"))owned(task.runId());goals.cancel(project,task.sourceId(),expectedRun,revision);}
      case "DEPENDENCY"->dependencies.cancel(project,task.sourceId(),revision);
      case "SCHEDULE"->schedules.cancel(project,task.sourceId(),revision);
      default->throw new OperationConflictException();
    }
    return tasks.get(project,session,id);
  }
  /** Stops the current execution while retaining existing Goal/Checkpoint resume state and budgets. */
  public TaskView suspend(String project,String session,String id,String expectedRun,long revision) {
    var task=tasks.get(project,session,id);expected(task,expectedRun,revision);
    if(!task.suspendSupported())throw new OperationConflictException();
    owned(task.runId());runs.cancel(task.runId());
    return tasks.get(project,session,id);
  }
  public TaskView resume(String project,String session,String id,String expectedRun,long revision) {
    var task=tasks.get(project,session,id);expected(task,expectedRun,revision);
    if(!task.resumeSupported())throw new OperationConflictException();
    switch(task.kind()) {
      case "CHECKPOINT"->checkpoints.resume(project,task.sourceId(),AgentRunContext.RequestSource.WEB,revision);
      case "GOAL"->{synchronized(goals){expected(tasks.get(project,session,id),expectedRun,revision);try{goals.run(project,task.sourceId());}catch(IllegalStateException unavailable){throw new OperationConflictException();}}}
      default->throw new OperationConflictException();
    }
    return tasks.get(project,session,id);
  }
  /** Adds bounded input to the selected Run's next iteration; never creates a Run. */
  public TaskView input(String project,String session,String id,String expectedRun,long revision,String message) {
    if(message==null||message.isBlank()||message.length()>16384)throw new IllegalArgumentException("Invalid Task guidance");
    var task=tasks.get(project,session,id);expected(task,expectedRun,revision);
    if(!task.inputSupported())throw new OperationConflictException();
    owned(task.runId());
    if(!inputs.offerIntervention(project,session,task.runId(),message))throw new OperationConflictException();
    return tasks.get(project,session,id);
  }
  private static void expected(TaskView task,String run,long revision) {
    if(revision<0)throw new IllegalArgumentException("Invalid Task revision");
    if(task.revision()!=revision||!Objects.equals(task.runId(),run))throw new OperationConflictException();
  }
  private void owned(String run) {if(run==null||!registry.ownsExecution(run))throw new OperationConflictException();}
}
