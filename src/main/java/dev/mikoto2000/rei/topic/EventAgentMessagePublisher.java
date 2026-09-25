package dev.mikoto2000.rei.topic;

import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.ConversationIds;

@Component
public class EventAgentMessagePublisher implements AgentMessagePublisher {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(EventAgentMessagePublisher.class);
  private final ConversationLogStore conversationLogStore;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final AgentActivityTracker activityTracker;
  private final dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator narrator;
  private BehaviorConversationHistoryAppender behaviorHistory;
  @org.springframework.beans.factory.annotation.Autowired
  void behaviorHistory(BehaviorConversationHistoryAppender history){this.behaviorHistory=history;}

  public EventAgentMessagePublisher(ConversationLogStore conversationLogStore,AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,AgentActivityTracker activityTracker,
      dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator narrator) {
    this.conversationLogStore=conversationLogStore;this.eventFactory=eventFactory;this.eventPublisher=eventPublisher;
    this.activityTracker=activityTracker;this.narrator=narrator;
    this.behaviorHistory=new BehaviorConversationHistoryAppender(conversationLogStore,null,null,null);
  }

  @Override public synchronized void publish(AgentMessage message) {
    synchronized(behaviorHistory.publicationLock()) {publishLocked(message);}
  }
  private void publishLocked(AgentMessage message) {
    if(message==null || message.content()==null || message.content().isBlank())return;
    boolean behavior=message.origin()==MessageOrigin.BEHAVIOR;
    BehaviorConversationHistoryAppender.Destination destination=null;
    if(behavior) {
      try {destination=behaviorHistory.capture(message);}
      catch(RuntimeException error){log.warn("Behavior conversation destination unavailable; notification continues");}
      if(destination!=null && destination.alreadyRecorded()) {append(destination,message);return;}
    }else conversationLogStore.append(ConversationIds.chat(),message.role(),message.content());
    eventPublisher.publish(owned(eventFactory.messageStarted(message.id(),message.role()),message,destination));
    eventPublisher.publish(owned(eventFactory.messageDelta(message.id(),message.content()),message,destination));
    eventPublisher.publish(owned(eventFactory.messageCompleted(message.id(),message.role(),message.content()),message,destination));
    if(behavior && destination!=null)append(destination,message);
    if("assistant".equalsIgnoreCase(message.role()))activityTracker.recordAgentCompleted(message.createdAt());
    if(behavior) {
      try {narrator.onPublished(message);}catch(RuntimeException error){log.warn("Behavior narration failed after message emission");}
    }else narrator.onPublished(message);
  }
  private void append(BehaviorConversationHistoryAppender.Destination destination,AgentMessage message) {
    try {behaviorHistory.append(destination,message);log.debug("Behavior conversation projection completed: id={}",message.id());}
    catch(RuntimeException error){log.warn("Behavior conversation persistence failed after emission: id={}",message.id());}
  }
  private AgentEvent owned(AgentEvent event,AgentMessage message,BehaviorConversationHistoryAppender.Destination destination) {
    if(destination==null)return event;
    return new AgentEvent(event.id(),event.sequence(),message.createdAt(),event.type(),event.version(),
        destination.conversationId(),null,null,message.id(),event.parentEventId(),event.payload(),destination.projectId());
  }
}
