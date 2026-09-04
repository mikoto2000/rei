package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class WebPageSummarizerServiceTest {

  @Test
  void fetchesExtractsSummarizesAndStoresConversationHistoryInOrder() {
    RecordingFetcher fetcher = new RecordingFetcher(UrlFetch.success("""
        <html><body><article>Article body only</article><nav>menu</nav></body></html>
        """));
    RecordingExtractor extractor = new RecordingExtractor("Article body only");
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("要約結果");
    RecordingConversationHistory history = new RecordingConversationHistory();
    WebPageSummarizerService service = new DefaultWebPageSummarizerService(
        fetcher, extractor, summarizationClient, history);

    SummaryResult result = service.summarize(URI.create("https://example.com/article"));

    assertEquals("要約結果", result.summary());
    assertEquals(URI.create("https://example.com/article"), fetcher.uri);
    assertEquals("Article body only", summarizationClient.content);
    assertFalse(summarizationClient.content.contains("<article>"));
    assertEquals(List.of(
        "user:次のWebページを要約してください: https://example.com/article",
        "assistant:要約結果"), history.messages);
  }

  @Test
  void doesNotCallLlmWhenFetchFails() {
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    WebPageSummarizerService service = new DefaultWebPageSummarizerService(
        new RecordingFetcher(UrlFetch.failure("NETWORK_ERROR", "timeout")),
        new RecordingExtractor("unused"),
        summarizationClient,
        new RecordingConversationHistory());

    SummarizationException error = assertThrows(SummarizationException.class,
        () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("NETWORK_ERROR", error.code());
    assertEquals(0, summarizationClient.calls);
  }

  @Test
  void doesNotCallLlmWhenExtractedContentIsEmpty() {
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    WebPageSummarizerService service = new DefaultWebPageSummarizerService(
        new RecordingFetcher(UrlFetch.success("<html></html>")),
        new RecordingExtractor(" "),
        summarizationClient,
        new RecordingConversationHistory());

    SummarizationException error = assertThrows(SummarizationException.class,
        () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("EMPTY_CONTENT", error.code());
    assertEquals(0, summarizationClient.calls);
  }

  @Test
  void recordsUserRequestButNotAssistantSummaryWhenLlmFails() {
    RecordingConversationHistory history = new RecordingConversationHistory();
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    summarizationClient.failure = new IllegalStateException("llm down");
    WebPageSummarizerService service = new DefaultWebPageSummarizerService(
        new RecordingFetcher(UrlFetch.success("<article>Article body</article>")),
        new RecordingExtractor("Article body"),
        summarizationClient,
        history);

    SummarizationException error = assertThrows(SummarizationException.class,
        () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("LLM_ERROR", error.code());
    assertEquals(List.of("user:次のWebページを要約してください: https://example.com/article"), history.messages);
  }

  private static final class RecordingFetcher implements WebContentFetcher {
    private final UrlFetch result;
    private URI uri;

    RecordingFetcher(UrlFetch result) {
      this.result = result;
    }

    @Override
    public UrlFetch fetch(URI uri) {
      this.uri = uri;
      return result;
    }
  }

  private static final class RecordingExtractor implements WebPageContentExtractor {
    private final String content;

    RecordingExtractor(String content) {
      this.content = content;
    }

    @Override
    public String extract(String url, String html) {
      assertTrue(html.contains("<"));
      return content;
    }
  }

  private static final class RecordingSummarizationClient implements SummarizationClient {
    private final String summary;
    private String content;
    private int calls;
    private RuntimeException failure;

    RecordingSummarizationClient(String summary) {
      this.summary = summary;
    }

    @Override
    public String summarize(String content) {
      calls++;
      this.content = content;
      if (failure != null) {
        throw failure;
      }
      return summary;
    }
  }

  private static final class RecordingConversationHistory implements ConversationHistoryAppender {
    private final List<String> messages = new ArrayList<>();

    @Override
    public void appendUserMessage(String content) {
      messages.add("user:" + content);
    }

    @Override
    public void appendAssistantMessage(String content) {
      messages.add("assistant:" + content);
    }
  }
}
