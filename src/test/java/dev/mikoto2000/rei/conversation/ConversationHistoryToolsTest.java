package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConversationHistoryToolsTest {

  @Test
  void delegatesSearchToService() {
    ConversationHistorySearchService service = mock(ConversationHistorySearchService.class);
    ConversationHistoryTools tools = new ConversationHistoryTools(service);
    List<ConversationSearchResult> expected = List.of(
        new ConversationSearchResult("chat:c1", "chat", "user", "2026-08-14T00:00:00Z", "summary", "content"));
    when(service.search("query", "chat", "user", "2026-08-01", null, 5)).thenReturn(expected);

    var results = tools.searchConversationHistory("query", "chat", "user", "2026-08-01", null, 5);

    assertThat(results).isSameAs(expected);
    verify(service).search("query", "chat", "user", "2026-08-01", null, 5);
  }

  @Test
  void delegatesDetailToService() {
    ConversationHistorySearchService service = mock(ConversationHistorySearchService.class);
    ConversationHistoryTools tools = new ConversationHistoryTools(service);
    ConversationHistoryDetail expected = new ConversationHistoryDetail("chat:c1", "chat", List.of());
    when(service.detail("chat:c1", 20)).thenReturn(expected);

    ConversationHistoryDetail detail = tools.getConversationHistory("chat:c1", 20);

    assertThat(detail).isSameAs(expected);
    verify(service).detail("chat:c1", 20);
  }
}
