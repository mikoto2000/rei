package dev.mikoto2000.rei.event;

import java.time.Instant;
import java.util.List;

/**
 * 実行状況の通知で受け渡す話題候補一覧Refreshed通知内容を表す。
 *
 * @param candidateCount 候補件数
 * @param topics 話題一覧
 * @param refreshedAt refreshed日時
 */
public record TopicCandidatesRefreshedPayload(
    int candidateCount, List<String> topics, Instant refreshedAt) implements AgentEventPayload {
  /** 受け取った情報からTopicCandidatesRefreshedPayloadを作成する。 */
  public TopicCandidatesRefreshedPayload {
    topics = List.copyOf(topics);
  }
}
