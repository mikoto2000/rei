package dev.mikoto2000.rei.event;

import org.springframework.stereotype.Component;

@Component
public class ProjectAgentEventSubscriber implements AutoCloseable {
  private final AgentEventBus.Subscription subscription,boundary;
  private final java.util.LinkedHashSet<String> persisted=new java.util.LinkedHashSet<>();
  public ProjectAgentEventSubscriber(AgentEventBus bus,ProjectAgentEventStore store) {
    boundary=bus.subscribeBoundary(event->{
      if(event.projectId()!=null){store.append(event);synchronized(persisted){persisted.add(event.id());if(persisted.size()>1024)persisted.remove(persisted.iterator().next());}}
    });
    subscription=bus.subscribe(event->{boolean already;synchronized(persisted){already=persisted.remove(event.id());}if(!already)store.onEvent(event);});
  }
  @Override @jakarta.annotation.PreDestroy public void close(){boundary.unsubscribe();subscription.unsubscribe();}
}
