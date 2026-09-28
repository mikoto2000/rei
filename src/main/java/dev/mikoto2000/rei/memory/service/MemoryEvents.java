package dev.mikoto2000.rei.memory.service;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.event.*;

@Component
public class MemoryEvents {
  private final AgentEventFactory factory;
  private final AgentEventPublisher publisher;
  public MemoryEvents(AgentEventFactory factory,AgentEventPublisher publisher) { this.factory=factory; this.publisher=publisher; }
  public void publish(AgentEventType type,String project,String session,String id,boolean preview,int turns,int candidates,int memories,String status) {
    try {
      var event=factory.memory(type,project,session,new MemoryLifecyclePayload(id,preview,turns,candidates,memories,status));
      if(type==AgentEventType.MEMORY_RETRIEVAL_COMPLETED) event=event.withOwnership(dev.mikoto2000.rei.core.chat.AgentRunScope.current());
      publisher.publish(event);
    }
    catch(RuntimeException error) {
      // Observability must not change the outcome of an already committed transaction.
      org.slf4j.LoggerFactory.getLogger(getClass()).warn("Memory event delivery failed ({})",error.getClass().getSimpleName());
    }
  }
}
