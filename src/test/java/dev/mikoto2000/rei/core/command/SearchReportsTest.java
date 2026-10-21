package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

/** 検索伝えるの結果と状態の契約を検証するテスト。 */
class SearchReportsTest {
  /** 逐次データ一覧回答を伝えることを確認する。 */
  @Test
  void reportStreamsAnswerAndSourcesThroughAnIndependentWriter() throws Exception {
    var client = mock(ChatClient.class);
    var request = mock(ChatClient.ChatClientRequestSpec.class, RETURNS_DEEP_STUBS);
    var model = mock(ModelHolderService.class);
    var knowledge = mock(SearchKnowledgeService.class);
    when(knowledge.search("query", 3, 5, 0.7, "source"))
        .thenReturn(
            new SearchKnowledgeResult(
                "query",
                List.of(
                    new dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult(
                        "doc", "source", 0, 0.9, "snippet")),
                WebSearchContext.primaryOnly(List.of()),
                null));
    when(client.prompt(any(Prompt.class))).thenReturn(request);
    when(request.advisors(any(java.util.function.Consumer.class))).thenReturn(request);
    when(request.stream().content()).thenReturn(reactor.core.publisher.Flux.just("an ", "answer"));
    var command = new SearchCommand(client, model, knowledge, new CommandCancellationService());
    var text = new StringBuilder();
    command.report("query", 3, 5, 0.7, "source", text::append);
    assertTrue(text.toString().contains("an answer"));
    assertTrue(text.toString().contains("=== sources ==="));
    assertTrue(text.toString().contains("- source"));
    verify(knowledge).search("query", 3, 5, 0.7, "source");
  }
}
