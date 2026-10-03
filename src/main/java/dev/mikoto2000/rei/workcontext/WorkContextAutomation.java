package dev.mikoto2000.rei.workcontext;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;

/** Queued only after durable terminal metadata; errors cannot change the original run outcome. */
@Component
public class WorkContextAutomation implements AutoCloseable {
  private final WorkContextService service;private final WorkContextProperties properties;private final AgentEventPublisher events;
  private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),
      runnable->{var thread=new Thread(runnable,"rei-work-context");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
  public WorkContextAutomation(WorkContextService service,WorkContextProperties properties,AgentEventPublisher events) {this.service=service;this.properties=properties;this.events=events;}
  public void afterStart(AgentRunContext context) {
    try {service.captureRunStart(context);}
    catch(RuntimeException error) {dev.mikoto2000.rei.core.chat.RunCancellation.propagate(error);org.slf4j.LoggerFactory.getLogger(getClass()).warn("Work-time Git metadata unavailable ({})",error.getClass().getSimpleName());}
  }
  public void afterTerminal(AgentRunContext context) {
    if(!properties.autoUpdate()||context.projectId()==null) return;
    try {worker.execute(()->{
      publish(context,AgentEventType.WORK_CONTEXT_UPDATE_STARTED,null,"started");
      try {
        var result=service.update(context.conversationId(),context.runId());
        publish(context,AgentEventType.WORK_CONTEXT_UPDATED,result.map(WorkContext::revision).orElse(null),"completed");
      } catch(RuntimeException error) {
        publish(context,AgentEventType.WORK_CONTEXT_UPDATE_FAILED,null,"failed:"+error.getClass().getSimpleName());
      }
    });} catch(RejectedExecutionException error) {publish(context,AgentEventType.WORK_CONTEXT_UPDATE_FAILED,null,"queue_unavailable; use /work update");}
  }
  private void publish(AgentRunContext owner,AgentEventType type,Long revision,String status) {
    try {events.publish(new AgentEvent(UUID.randomUUID().toString(),0,Instant.now(),type,1,owner.conversationId(),null,
        null,owner.runId(),null,new WorkContextPayload(owner.runId(),revision,status),owner.projectId()));}
    catch(RuntimeException error) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Work Context event delivery unavailable");}
  }
  @jakarta.annotation.PreDestroy @Override public void close() {
    worker.shutdown();
    try {if(!worker.awaitTermination(2,TimeUnit.SECONDS))worker.shutdownNow();}
    catch(InterruptedException error) {worker.shutdownNow();Thread.currentThread().interrupt();}
  }
}
