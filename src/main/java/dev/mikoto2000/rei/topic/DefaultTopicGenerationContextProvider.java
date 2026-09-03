package dev.mikoto2000.rei.topic;

import java.time.Clock;

import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.working.FileReference;
import dev.mikoto2000.rei.core.working.WorkingSet;

@Component
public class DefaultTopicGenerationContextProvider implements TopicGenerationContextProvider {
  private final ConversationLogStore conversationLogStore;
  private final java.util.Optional<WorkingSet> workingSet;
  private final Clock clock;

  public DefaultTopicGenerationContextProvider(ConversationLogStore conversationLogStore,
      java.util.Optional<WorkingSet> workingSet, Clock clock) {
    this.conversationLogStore = conversationLogStore;
    this.workingSet = workingSet;
    this.clock = clock;
  }

  @Override
  public TopicGenerationContext currentContext() {
    java.time.Instant now = java.time.Instant.now(clock);
    java.util.List<dev.mikoto2000.rei.conversation.ConversationLogEntry> entries = conversationLogStore.readAll();
    java.util.List<ConversationTopicMessage> conversation = entries.stream()
        .filter(entry -> "chat".equals(entry.scope()))
        .skip(Math.max(0, entries.size() - 12))
        .map(entry -> new ConversationTopicMessage(entry.speaker(), entry.content(), entry.timestamp().toInstant()))
        .toList();
    java.util.List<WorkingSetTopicItem> files = workingSet.map(WorkingSet::getFiles).orElse(java.util.List.of())
        .stream()
        .map(this::workingSetTopicItem)
        .toList();
    return new TopicGenerationContext(conversation, files, now, java.util.List.of());
  }

  private WorkingSetTopicItem workingSetTopicItem(FileReference reference) {
    return new WorkingSetTopicItem(reference.path(), reference.accessType(), reference.lastAccessedAt().toInstant());
  }
}
