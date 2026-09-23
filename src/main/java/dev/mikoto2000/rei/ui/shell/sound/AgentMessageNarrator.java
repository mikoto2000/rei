package dev.mikoto2000.rei.ui.shell.sound;

import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Narrates delivered behavior notices without holding the behavior service's delivery lock. */
@Component
public class AgentMessageNarrator {
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AgentMessageNarrator.class);
  private final ChatResponseNarrator narrator;
  private final SoundNotificationProperties settings;
  private final Executor executor;
  public AgentMessageNarrator(ChatResponseNarrator narrator,SoundNotificationProperties settings,
      @Qualifier("agentRunExecutor") Executor executor) {
    this.narrator=narrator;this.settings=settings;this.executor=executor;
  }
  public void onPublished(AgentMessage message) {
    if(!settings.isEnabled() || message==null || message.origin()!=MessageOrigin.BEHAVIOR
        || !"assistant".equalsIgnoreCase(message.role()) || message.content()==null || message.content().isBlank())return;
    try {
      executor.execute(()->{
        try {if(settings.isEnabled())narrator.narrateCompletedRun(message.content());}
        catch(RuntimeException error){log.warn("Behavior narration failed ({})",error.getClass().getSimpleName());}
      });
    } catch(RejectedExecutionException closing) {log.debug("Behavior narration skipped during shutdown");}
  }
}
