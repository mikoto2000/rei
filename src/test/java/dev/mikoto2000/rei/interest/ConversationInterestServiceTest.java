package dev.mikoto2000.rei.interest;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class ConversationInterestServiceTest {

  @Test
  void discoverCandidatesWithPastQueriesPassesPastQueriesToExtractor() {
    ConversationHistoryService historyService = mock(ConversationHistoryService.class);
    InterestTopicExtractor extractor = mock(InterestTopicExtractor.class);
    InterestProperties properties = new InterestProperties();

    ConversationInterestService service = new ConversationInterestService(historyService, extractor, properties);

    List<ConversationSnippet> snippets = List.of(
        new ConversationSnippet("c1", "neovim の話", OffsetDateTime.now(ZoneOffset.UTC)));
    List<String> pastQueries = List.of("old query 1", "old query 2");

    when(historyService.findRecentUserMessages(anyInt(), anyInt())).thenReturn(snippets);
    when(extractor.extract(eq(snippets), anyInt(), eq(pastQueries))).thenReturn(List.of());

    // pastQueries を渡して呼び出す
    service.discoverCandidates(pastQueries);

    // extract(snippets, maxTopics, pastQueries) が呼ばれることを検証
    verify(extractor).extract(eq(snippets), anyInt(), eq(pastQueries));
  }

  @Test
  void discoverFallbackCandidatesBroadensMaxTopicsAndThreshold() {
    ConversationHistoryService historyService = mock(ConversationHistoryService.class);
    InterestTopicExtractor extractor = mock(InterestTopicExtractor.class);
    InterestProperties properties = new InterestProperties();
    properties.setMaxTopics(3);
    properties.setMinScore(0.6);

    ConversationInterestService service = new ConversationInterestService(historyService, extractor, properties);

    List<ConversationSnippet> snippets = List.of(
        new ConversationSnippet("c1", "neovim の話", OffsetDateTime.now(ZoneOffset.UTC)));
    List<String> pastQueries = List.of("old query 1");
    InterestTopicCandidate broad = new InterestTopicCandidate("topic", "reason", "query", 0.45);

    when(historyService.findRecentUserMessages(anyInt(), anyInt())).thenReturn(snippets);
    when(extractor.extract(eq(snippets), eq(6), eq(pastQueries))).thenReturn(List.of(broad));

    List<InterestTopicCandidate> candidates = service.discoverFallbackCandidates(pastQueries);

    assertEquals(List.of(broad), candidates);
    verify(extractor).extract(eq(snippets), eq(6), eq(pastQueries));
  }
}
