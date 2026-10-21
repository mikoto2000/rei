package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.core.working.FileReference;
import dev.mikoto2000.rei.core.working.WorkingSet;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** 既定話題生成文脈を用途や設定に応じて提供する窓口。 */
@Component
public class DefaultTopicGenerationContextProvider implements TopicGenerationContextProvider {
  /** 会話ログ保存先。 */
  private final ConversationLogStore conversationLogStore;

  /** 作業中集合。 */
  private final java.util.Optional<WorkingSet> workingSet;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、DefaultTopicGenerationContextProviderを初期化する。 */
  public DefaultTopicGenerationContextProvider(
      ConversationLogStore conversationLogStore,
      java.util.Optional<WorkingSet> workingSet,
      Clock clock) {
    this.conversationLogStore = conversationLogStore;
    this.workingSet = workingSet;
    this.clock = clock;
  }

  /** 話題の管理で用いる現在文脈を返す。 */
  @Override
  public TopicGenerationContext currentContext() {
    java.time.Instant now = java.time.Instant.now(clock);
    java.util.List<dev.mikoto2000.rei.conversation.ConversationLogEntry> entries =
        conversationLogStore.readAll();
    java.util.List<ConversationTopicMessage> conversation =
        entries.stream()
            .filter(entry -> "chat".equals(entry.scope()))
            .skip(Math.max(0, entries.size() - 12))
            .map(
                entry ->
                    new ConversationTopicMessage(
                        entry.speaker(), entry.content(), entry.timestamp().toInstant()))
            .toList();
    java.util.List<WorkingSetTopicItem> files =
        workingSet.map(WorkingSet::getFiles).orElse(java.util.List.of()).stream()
            .map(this::workingSetTopicItem)
            .toList();
    return new TopicGenerationContext(conversation, files, now, java.util.List.of());
  }

  /** 話題の管理で用いる作業中集合話題項目を返す。 */
  private WorkingSetTopicItem workingSetTopicItem(FileReference reference) {
    return new WorkingSetTopicItem(
        reference.path(), reference.accessType(), reference.lastAccessedAt().toInstant());
  }
}
