package dev.mikoto2000.rei.temporal;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.application.session.SessionRepository;

/** Admission shares the existing project FIFO; the durable claim precedes all external execution. */
@Component
@EnableConfigurationProperties(AgentSchedulerProperties.class)
public class AgentScheduleDispatcher {
  @FunctionalInterface interface Gateway {
    void dispatch(PersistentAgentScheduler.Entry entry,Consumer<ChatExecutionResult> completed);
  }
  private final PersistentAgentScheduler schedules;
  private final AgentSchedulerProperties properties;
  private final ToolPermissionProperties permissions;
  private final Gateway gateway;
  private final java.util.function.Predicate<PersistentAgentScheduler.Entry> inFlight;
  private final java.util.concurrent.atomic.AtomicReference<PersistentAgentScheduler.Entry> currentClaim=new java.util.concurrent.atomic.AtomicReference<>();
  private final AtomicBoolean busy=new AtomicBoolean();
  private dev.mikoto2000.rei.application.run.RunRegistry runRegistry;
  private dev.mikoto2000.rei.application.run.RunService runLifecycle;
  @Autowired(required=false)
  public void configureRunTracking(dev.mikoto2000.rei.application.run.RunRegistry registry,
      dev.mikoto2000.rei.application.run.RunService lifecycle) {
    this.runRegistry=registry;this.runLifecycle=lifecycle;
  }
  @Autowired
  public AgentScheduleDispatcher(PersistentAgentScheduler schedules,AgentSchedulerProperties properties,
      ToolPermissionProperties permissions,ProjectService projects,SessionRepository sessions,
      ConversationInputRouter router,ChatExecutionService chat) {
    this.schedules=schedules;this.properties=properties;this.permissions=permissions;
    this.inFlight=entry->router.containsRun(entry.projectId(),entry.runId());
    this.gateway=(entry,completed)->{
      var root=Path.of(entry.projectRoot());
      if(!validProject(entry,projects))
        throw new IllegalStateException("Scheduled project is missing or relocated; create a new schedule");
      var session=sessions.findById(entry.task().conversationId()).orElseThrow(()->new IllegalStateException("Scheduled session no longer exists"));
      if(!session.projectId().equals(entry.projectId()))throw new IllegalStateException("Scheduled session belongs to a different project");
      var owner=new AgentRunContext(entry.runId(),session.sessionId(),root,entry.projectId(),AgentRunContext.RequestSource.WEB);
      boolean registered=false;
      try {
      if(runRegistry!=null) {
        runRegistry.register(owner);registered=true;
        runLifecycle.onQueuedCancellation(entry.runId(),()->completed.accept(ChatExecutionResult.cancelled()));
      }
      router.submitOperation(owner,()->{
        ChatExecutionResult result;
        try {
          if(!schedules.activeClaim(entry)) result=ChatExecutionResult.cancelled();
          else {
          if(!validProject(entry,projects))throw new IllegalStateException("Scheduled project was relocated while queued");
          var current=sessions.findById(owner.conversationId()).orElseThrow(()->new IllegalStateException("Scheduled session no longer exists"));
          if(!current.projectId().equals(owner.projectId()))throw new IllegalStateException("Scheduled session ownership changed");
          result=chat.execute(owner,entry.task().action(),new UserInterventionQueue());
          }
        }
        catch(RuntimeException error){result=ChatExecutionResult.failed(error.getClass().getSimpleName());}
        if(runLifecycle!=null) {
          var terminal=result.status()==ChatExecutionResult.Status.CANCELLED?dev.mikoto2000.rei.application.run.RunStatus.CANCELLED
              :result.success()?dev.mikoto2000.rei.application.run.RunStatus.COMPLETED:dev.mikoto2000.rei.application.run.RunStatus.FAILED;
          runLifecycle.finishMissingTerminal(owner,terminal);
          if(runRegistry.get(entry.runId()).status()==dev.mikoto2000.rei.application.run.RunStatus.CANCELLED)result=ChatExecutionResult.cancelled();
        }
        completed.accept(result);
      },work->{
        Runnable execute=()->{try {work.run();}catch(RuntimeException error){completed.accept(ChatExecutionResult.failed(error.getClass().getSimpleName()));}};
        if(runLifecycle!=null)runLifecycle.execute(owner,execute);else execute.run();
      });
      }catch(RuntimeException|Error error){if(registered){runLifecycle.forgetQueuedCancellation(entry.runId());runRegistry.forget(entry.runId());}throw error;}
    };
  }
  AgentScheduleDispatcher(PersistentAgentScheduler schedules,AgentSchedulerProperties properties,ToolPermissionProperties permissions,Gateway gateway) {
    this(schedules,properties,permissions,gateway,entry->true);
  }
  AgentScheduleDispatcher(PersistentAgentScheduler schedules,AgentSchedulerProperties properties,ToolPermissionProperties permissions,
      Gateway gateway,java.util.function.Predicate<PersistentAgentScheduler.Entry> inFlight) {
    this.schedules=schedules;this.properties=properties;this.permissions=permissions;this.gateway=gateway;this.inFlight=inFlight;
  }
  private static boolean validProject(PersistentAgentScheduler.Entry entry,ProjectService projects) {
    var root=Path.of(entry.projectRoot());
    try {
      return Files.isDirectory(root)&&root.toRealPath().equals(root)&&projects.registeredProjects().stream()
          .anyMatch(p->p.id().equals(entry.projectId())&&p.root().equals(root));
    } catch(java.io.IOException error){return false;}
  }
  @Scheduled(fixedDelayString="${rei.agent-scheduler.check-interval-ms:5000}")
  public synchronized void tick() {
    if(!properties.enabled()||!permissions.enabled()||!busy.compareAndSet(false,true))return;
    PersistentAgentScheduler.Entry claim=null;
    try {
      var next=schedules.claimDue();if(next.isEmpty()){busy.set(false);return;}claim=next.get();currentClaim.set(claim);
      var owned=claim;
      gateway.dispatch(owned,result->{
        try {if(schedules.activeClaim(owned))schedules.finish(owned,switch(result.status()) {
          case SUCCESS -> "COMPLETED";case FAILED -> "FAILED";case CANCELLED -> "CANCELLED";
        },result.success()?result.text():result.errorMessage());}
        finally {release(owned);}
      });
    } catch(RuntimeException error) {
      try {if(claim!=null&&schedules.activeClaim(claim))schedules.finish(claim,"FAILED",error.getMessage());}
      finally {if(claim==null)busy.set(false);else release(claim);}
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Schedule dispatch failed: {}",error.getClass().getSimpleName());
    }
  }
  public synchronized PersistentAgentScheduler.Entry reconcile(String project,String id,String expectedRunId) {
    var entry=schedules.get(project,id);
    if(inFlight.test(entry))throw new IllegalStateException("Schedule Run is queued or executing; stop it through normal Run controls");
    var result=schedules.reconcile(project,id,expectedRunId);
    var owned=currentClaim.get();
    if(owned!=null&&owned.task().id().equals(id)&&owned.projectId().equals(project)&&java.util.Objects.equals(owned.runId(),expectedRunId))release(owned);
    return result;
  }
  private synchronized void release(PersistentAgentScheduler.Entry owned) {
    if(currentClaim.compareAndSet(owned,null))busy.set(false);
  }
}
