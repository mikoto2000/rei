package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 情報への関心実行待ちの結果と状態の契約を検証するテスト。 */
class CuriosityQueueTest {

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void addsAndFindsPendingCuriosityByPriority() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("low", "low question", 0.2, null));
    queue.add(item("high", "high question", 0.9, null));

    List<CuriosityItem> candidates = queue.findCandidates(new CuriosityQuery(now(), 10));

    assertEquals("high", candidates.getFirst().id());
    assertEquals(2, candidates.size());
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void marksUsedAndDismissed() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("used", "used question", 0.9, null));
    queue.add(item("dismissed", "dismissed question", 0.8, null));

    queue.markUsed("used");
    queue.dismiss("dismissed");

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  /** 話題の管理で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void expiredItemIsNotCandidate() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("expired", "old question", 0.9, Instant.parse("2026-09-01T00:00:00Z")));

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  /** 話題の管理で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicateQuestionIsSuppressed() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("a", "Working Set 測定", 0.9, null));
    queue.add(item("b", " working  set 測定 ", 0.8, null));

    assertEquals(1, queue.findCandidates(new CuriosityQuery(now(), 10)).size());
  }

  /** 話題の管理で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void curiosityConvertsToTopicCandidateAndIsMarkedUsedAfterSpeech() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    queue.add(item("a", "測定を確認する", 0.9, Instant.parse("2026-10-02T00:00:00Z")));
    CuriosityTopicCandidateGenerator generator =
        new CuriosityTopicCandidateGenerator(queue, properties);

    List<TopicCandidate> candidates = generator.generate(context());

    assertEquals(1, candidates.size());
    assertEquals(TopicSource.CURIOSITY_QUEUE, candidates.getFirst().source());
  }

  /** {@code item} の条件を再現するための補助情報を返す。 */
  private CuriosityItem item(String id, String question, double priority, Instant expiresAt) {
    return new CuriosityItem(
        id,
        question,
        "reason",
        TopicSource.CONVERSATION,
        priority,
        Instant.parse("2026-09-02T00:00:00Z"),
        expiresAt,
        CuriosityStatus.PENDING);
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private TopicGenerationContext context() {
    return new TopicGenerationContext(List.of(), List.of(), now(), List.of());
  }

  /** {@code now} の条件を再現するための補助情報を返す。 */
  private Instant now() {
    return Instant.parse("2026-09-02T00:00:00Z");
  }
}
