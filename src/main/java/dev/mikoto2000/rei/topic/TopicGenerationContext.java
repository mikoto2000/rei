package dev.mikoto2000.rei.topic;

import java.time.Instant;
import java.util.List;

/**
 * 話題の管理で受け渡す話題生成文脈を表す。
 *
 * @param recentConversation 直近会話
 * @param workingSet 作業中集合
 * @param currentTime 現在時刻
 * @param recentTopics 直近話題一覧
 */
public record TopicGenerationContext(
    List<ConversationTopicMessage> recentConversation,
    List<WorkingSetTopicItem> workingSet,
    Instant currentTime,
    List<String> recentTopics) {

  /** 受け取った情報からTopicGenerationContextを作成する。 */
  public TopicGenerationContext {
    recentConversation = recentConversation == null ? List.of() : List.copyOf(recentConversation);
    workingSet = workingSet == null ? List.of() : List.copyOf(workingSet);
    if (currentTime == null) throw new IllegalArgumentException("currentTime must not be null");
    recentTopics = recentTopics == null ? List.of() : List.copyOf(recentTopics);
  }
}
