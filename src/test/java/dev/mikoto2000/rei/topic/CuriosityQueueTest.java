package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class CuriosityQueueTest {

  @Test
  void addsAndFindsPendingCuriosityByPriority() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("low", "low question", 0.2, null));
    queue.add(item("high", "high question", 0.9, null));

    List<CuriosityItem> candidates = queue.findCandidates(new CuriosityQuery(now(), 10));

    assertEquals("high", candidates.getFirst().id());
    assertEquals(2, candidates.size());
  }

  @Test
  void marksUsedAndDismissed() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("used", "used question", 0.9, null));
    queue.add(item("dismissed", "dismissed question", 0.8, null));

    queue.markUsed("used");
    queue.dismiss("dismissed");

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  @Test
  void expiredItemIsNotCandidate() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("expired", "old question", 0.9, Instant.parse("2026-09-01T00:00:00Z")));

    assertTrue(queue.findCandidates(new CuriosityQuery(now(), 10)).isEmpty());
  }

  @Test
  void duplicateQuestionIsSuppressed() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    queue.add(item("a", "Working Set 測定", 0.9, null));
    queue.add(item("b", " working  set 測定 ", 0.8, null));

    assertEquals(1, queue.findCandidates(new CuriosityQuery(now(), 10)).size());
  }

  @Test
  void curiosityConvertsToTopicCandidateAndIsMarkedUsedAfterSpeech() {
    InMemoryCuriosityQueue queue = new InMemoryCuriosityQueue();
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    queue.add(item("a", "測定を確認する", 0.9, Instant.parse("2026-10-02T00:00:00Z")));
    CuriosityTopicCandidateGenerator generator = new CuriosityTopicCandidateGenerator(queue, properties);

    List<TopicCandidate> candidates = generator.generate(context());

    assertEquals(1, candidates.size());
    assertEquals(TopicSource.CURIOSITY_QUEUE, candidates.getFirst().source());
  }

  private CuriosityItem item(String id, String question, double priority, Instant expiresAt) {
    return new CuriosityItem(id, question, "reason", TopicSource.CONVERSATION, priority,
        Instant.parse("2026-09-02T00:00:00Z"), expiresAt, CuriosityStatus.PENDING);
  }

  private TopicGenerationContext context() {
    return new TopicGenerationContext(List.of(), List.of(), now(), List.of());
  }

  private Instant now() {
    return Instant.parse("2026-09-02T00:00:00Z");
  }
}
