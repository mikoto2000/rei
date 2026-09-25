package dev.mikoto2000.rei.conversation;

import java.util.*;
import org.springframework.stereotype.Component;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.llm.ConversationIds;

/** Same notification, two history projections. No generation, evaluation, user activity, or message events. */
@Component
public final class BehaviorConversationHistoryAppender {
  public record Destination(String conversationId,String projectId,boolean alreadyRecorded) {}
  private final ConversationLogStore logs;
  private final ConversationTurnStore turns;
  private final SessionRepository sessions;
  private final ProjectService projects;
  public BehaviorConversationHistoryAppender(ConversationLogStore logs,ConversationTurnStore turns,SessionRepository sessions,ProjectService projects) {
    this.logs=logs;this.turns=turns;this.sessions=sessions;this.projects=projects;
  }
  public Object publicationLock() {return projects==null?this:projects.notificationLock();}
  public Destination capture(AgentMessage message) {
    var existing=logs.findNotification(message.id());
    if(existing.isPresent()) {
      var id=existing.get().conversationId();return new Destination(id,ProjectStorage.projectId(id),true);
    }
    if(projects==null) {
      var id=ConversationIds.currentChat();return new Destination(id,ProjectStorage.projectId(id),false);
    }
    var selection=projects.notificationSelection();var project=selection.project();
    var id=selection.conversationId()==null?project.conversationId(ConversationIds.chat()):selection.conversationId();
    return new Destination(id,project.id(),false);
  }
  public void append(Destination destination,AgentMessage message) {
    // Metadata is allowlisted. Activity evidence, screenshots and model output are never copied.
    var metadata=new LinkedHashMap<String,String>();
    for(var key:List.of("severity","triggerType"))if(message.metadata().containsKey(key))metadata.put(key,message.metadata().get(key));
    var entry=logs.appendNotification(destination.conversationId(),message.id(),message.content(),message.createdAt(),metadata);
    String projectId=ProjectStorage.projectId(entry.conversationId());
    if(sessions!=null && projectId!=null)synchronized(sessions) {
      var at=entry.timestamp().toInstant();
      var metadataRow=sessions.findById(entry.conversationId()).map(s->s.touched(at))
          .orElseGet(()->new SessionMetadata(entry.conversationId(),projectId,SessionTitle.from(entry.content()),at,at));
      if(!metadataRow.projectId().equals(projectId))throw new IllegalArgumentException("Session ownership mismatch");
      sessions.accept(metadataRow,()->{});
    }
    if(turns!=null)turns.appendAssistantNotification(entry);
    if(projects!=null && projectId!=null)projects.selectNotificationDefault(projectId,entry.conversationId());
  }
}
