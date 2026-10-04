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
  private final AtomicBoolean busy=new AtomicBoolean();
  @Autowired
  public AgentScheduleDispatcher(PersistentAgentScheduler schedules,AgentSchedulerProperties properties,
      ToolPermissionProperties permissions,ProjectService projects,SessionRepository sessions,
      ConversationInputRouter router,ChatExecutionService chat) {
    this(schedules,properties,permissions,(entry,completed)->{
      var root=Path.of(entry.projectRoot());
      if(!validProject(entry,projects))
        throw new IllegalStateException("Scheduled project is missing or relocated; create a new schedule");
      var session=sessions.findById(entry.task().conversationId()).orElseThrow(()->new IllegalStateException("Scheduled session no longer exists"));
      if(!session.projectId().equals(entry.projectId()))throw new IllegalStateException("Scheduled session belongs to a different project");
      var owner=new AgentRunContext(entry.runId(),session.sessionId(),root,entry.projectId(),AgentRunContext.RequestSource.WEB);
      router.submitOperation(owner,()->{
        ChatExecutionResult result;
        try {
          if(!validProject(entry,projects))throw new IllegalStateException("Scheduled project was relocated while queued");
          var current=sessions.findById(owner.conversationId()).orElseThrow(()->new IllegalStateException("Scheduled session no longer exists"));
          if(!current.projectId().equals(owner.projectId()))throw new IllegalStateException("Scheduled session ownership changed");
          result=chat.execute(owner,entry.task().action(),new UserInterventionQueue());
        }
        catch(RuntimeException error){result=ChatExecutionResult.failed(error.getClass().getSimpleName());}
        completed.accept(result);
      },work->{try {work.run();}catch(RuntimeException error){completed.accept(ChatExecutionResult.failed(error.getClass().getSimpleName()));}});
    });
  }
  AgentScheduleDispatcher(PersistentAgentScheduler schedules,AgentSchedulerProperties properties,ToolPermissionProperties permissions,Gateway gateway) {
    this.schedules=schedules;this.properties=properties;this.permissions=permissions;this.gateway=gateway;
  }
  private static boolean validProject(PersistentAgentScheduler.Entry entry,ProjectService projects) {
    var root=Path.of(entry.projectRoot());
    try {
      return Files.isDirectory(root)&&root.toRealPath().equals(root)&&projects.registeredProjects().stream()
          .anyMatch(p->p.id().equals(entry.projectId())&&p.root().equals(root));
    } catch(java.io.IOException error){return false;}
  }
  @Scheduled(fixedDelayString="${rei.agent-scheduler.check-interval-ms:5000}")
  public void tick() {
    if(!properties.enabled()||!permissions.enabled()||!busy.compareAndSet(false,true))return;
    PersistentAgentScheduler.Entry claim=null;
    try {
      var next=schedules.claimDue();if(next.isEmpty()){busy.set(false);return;}claim=next.get();
      var owned=claim;
      gateway.dispatch(owned,result->{
        try {schedules.finish(owned,switch(result.status()) {
          case SUCCESS -> "COMPLETED";case FAILED -> "FAILED";case CANCELLED -> "CANCELLED";
        },result.success()?result.text():result.errorMessage());}
        finally {busy.set(false);}
      });
    } catch(RuntimeException error) {
      try {if(claim!=null)schedules.finish(claim,"FAILED",error.getMessage());}
      finally {busy.set(false);}
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Schedule dispatch failed: {}",error.getClass().getSimpleName());
    }
  }
}
