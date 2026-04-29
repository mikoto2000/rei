package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class InterestTopicExtractorTest {

  /**
   * pastQueries を渡した場合に既存の extract(snippets, maxTopics) と同じ結果を返すことを検証する。
   * デフォルトメソッドが既存の extract に委譲していることを確認する。
   */
  @Test
  void extractWithPastQueriesDelegatesToExistingExtract() {
    // 固定の結果を返すスタブ実装
    InterestTopicCandidate expected = new InterestTopicCandidate("Neovim", "reason", "neovim query", 0.9);
    InterestTopicExtractor extractor = (snippets, maxTopics) -> List.of(expected);

    List<ConversationSnippet> snippets = List.of(
        new ConversationSnippet("c1", "neovim の話", OffsetDateTime.now(ZoneOffset.UTC)));
    List<String> pastQueries = List.of("old query 1", "old query 2");

    // pastQueries 付きの extract を呼び出す
    List<InterestTopicCandidate> result = extractor.extract(snippets, 3, pastQueries);

    assertEquals(1, result.size());
    assertEquals("Neovim", result.getFirst().topic());
  }
}
