package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 入力先記憶話題候補保存先の結果と状態の契約を検証するテスト。 */
class InMemoryTopicCandidateStoreTest {

  /** 現在候補一覧範囲内最大経過時間を返すことを確認する。 */
  @Test
  void returnsCurrentCandidatesWithinMaxAge() {
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    TopicCandidate candidate = candidate();
    store.replace(List.of(candidate), Instant.parse("2026-09-02T00:00:00Z"));

    assertEquals(
        List.of(candidate),
        store.currentCandidates(Instant.parse("2026-09-02T00:10:00Z"), Duration.ofMinutes(30)));
  }

  /** 空のを返すことを確認する。 */
  @Test
  void returnsEmptyWhenCandidatesAreStale() {
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), Instant.parse("2026-09-02T00:00:00Z"));

    assertEquals(
        List.of(),
        store.currentCandidates(Instant.parse("2026-09-02T00:31:00Z"), Duration.ofMinutes(30)));
  }

  /** {@code candidate} の条件を再現するための補助情報を返す。 */
  private TopicCandidate candidate() {
    return new TopicCandidate(
        "id",
        "topic",
        "reason",
        TopicType.FOLLOW_UP,
        TopicSource.CONVERSATION,
        1,
        1,
        1,
        0,
        1,
        Instant.parse("2026-09-02T00:00:00Z"));
  }
}
