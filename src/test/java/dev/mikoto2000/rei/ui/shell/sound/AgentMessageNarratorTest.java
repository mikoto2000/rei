package dev.mikoto2000.rei.ui.shell.sound;

import dev.mikoto2000.rei.topic.*;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentMessageNarratorTest {
  @Test void behaviorIsNarratedAsynchronouslyWithoutChangingCommandFlag() {
    var sound=mock(SoundNotificationService.class);var narrator=new ChatResponseNarrator(sound);
    var settings=new SoundNotificationProperties();settings.setEnabled(true);var tasks=new ArrayList<Runnable>();
    var messages=new AgentMessageNarrator(narrator,settings,tasks::add);
    messages.onPublished(message(MessageOrigin.BEHAVIOR,"assistant","**休憩**しましょう"));
    verifyNoInteractions(sound);assertEquals(1,tasks.size());tasks.getFirst().run();
    verify(sound).notify("休憩しましょう");assertFalse(narrator.wasNarrated());
  }
  @Test void disabledOtherOriginsAndEmptyMessagesAreNotQueued() {
    var settings=new SoundNotificationProperties();var narrator=mock(ChatResponseNarrator.class);var tasks=new ArrayList<Runnable>();
    var messages=new AgentMessageNarrator(narrator,settings,tasks::add);
    messages.onPublished(message(MessageOrigin.BEHAVIOR,"assistant","notice"));settings.setEnabled(true);
    messages.onPublished(message(MessageOrigin.TOPIC_GENERATOR,"assistant","topic"));
    messages.onPublished(message(MessageOrigin.NORMAL_RESPONSE,"assistant","answer"));
    messages.onPublished(message(MessageOrigin.BEHAVIOR,"user","user"));
    messages.onPublished(message(MessageOrigin.BEHAVIOR,"assistant"," "));messages.onPublished(null);
    assertTrue(tasks.isEmpty());verifyNoInteractions(narrator);
  }
  @Test void audioFailureDoesNotFailMessageDelivery() {
    var settings=new SoundNotificationProperties();settings.setEnabled(true);var narrator=mock(ChatResponseNarrator.class);
    doThrow(new IllegalStateException()).when(narrator).narrateCompletedRun(anyString());
    assertDoesNotThrow(()->new AgentMessageNarrator(narrator,settings,Runnable::run).onPublished(message(MessageOrigin.BEHAVIOR,"assistant","notice")));
    assertDoesNotThrow(()->new AgentMessageNarrator(narrator,settings,task->{throw new java.util.concurrent.RejectedExecutionException();}).onPublished(message(MessageOrigin.BEHAVIOR,"assistant","notice")));
  }
  private AgentMessage message(MessageOrigin origin,String role,String content) {return new AgentMessage("id",role,content,origin,Instant.now());}
}
