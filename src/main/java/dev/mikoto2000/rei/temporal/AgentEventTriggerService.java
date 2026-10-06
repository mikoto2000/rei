package dev.mikoto2000.rei.temporal;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import dev.mikoto2000.rei.event.*;

/** Live facts plus bounded durable replay. The existing dispatcher owns all execution. */
@Component
public class AgentEventTriggerService implements AutoCloseable {
  private final PersistentAgentScheduler schedules;
  private final ProjectAgentEventStore store;
  private final AgentEventBus.Subscription subscription;
  private String afterProject="";
  public AgentEventTriggerService(PersistentAgentScheduler schedules,AgentEventBus bus,ProjectAgentEventStore store) {
    this.schedules=schedules;this.store=store;subscription=bus.subscribe(schedules::signalEvent);
  }
  @Scheduled(fixedDelayString="${rei.agent-scheduler.event-check-interval-ms:5000}")
  public synchronized void tick() {
    schedules.expireEventWaits();
    var projects=schedules.waitingEventProjects(afterProject);
    if(projects.isEmpty()) {afterProject="";projects=schedules.waitingEventProjects(afterProject);}
    for(String project:projects) {
      afterProject=project;
      try {
        var cursor=schedules.replayCursor(project);
        var page=store.readPage(project,cursor.offset(),cursor.discardingLine());
        for(var event:page.events())if(project.equals(event.projectId()))schedules.signalEvent(event);
        schedules.saveReplayCursor(project,cursor,page.nextOffset(),page.discardingLine());
      } catch(RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Schedule event replay failed: {}",error.getClass().getSimpleName());
      }
    }
  }
  @Override @jakarta.annotation.PreDestroy public void close(){subscription.unsubscribe();}
}
