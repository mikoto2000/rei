package dev.mikoto2000.rei.summarize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** WebページSummarizerサービスの結果と状態の契約を検証するテスト。 */
class WebPageSummarizerServiceTest {

  /** 会話履歴を保存することを確認する。 */
  @Test
  void fetchesExtractsSummarizesAndStoresConversationHistoryInOrder() {
    RecordingFetcher fetcher =
        new RecordingFetcher(
            UrlFetch.success(
                """
        <html><body><article>Article body only</article><nav>menu</nav></body></html>
        """));
    RecordingExtractor extractor = new RecordingExtractor("Article body only");
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("要約結果");
    RecordingConversationHistory history = new RecordingConversationHistory();
    WebPageSummarizerService service =
        new DefaultWebPageSummarizerService(fetcher, extractor, summarizationClient, history);

    SummaryResult result = service.summarize(URI.create("https://example.com/article"));

    assertEquals("要約結果", result.summary());
    assertEquals(URI.create("https://example.com/article"), fetcher.uri);
    assertEquals("Article body only", summarizationClient.content);
    assertFalse(summarizationClient.content.contains("<article>"));
    assertEquals(
        List.of("user:次のWebページを要約してください: https://example.com/article", "assistant:要約結果"),
        history.messages);
  }

  /** 情報の要約で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void doesNotCallLlmWhenFetchFails() {
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    WebPageSummarizerService service =
        new DefaultWebPageSummarizerService(
            new RecordingFetcher(UrlFetch.failure("NETWORK_ERROR", "timeout")),
            new RecordingExtractor("unused"),
            summarizationClient,
            new RecordingConversationHistory());

    SummarizationException error =
        assertThrows(
            SummarizationException.class,
            () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("NETWORK_ERROR", error.code());
    assertEquals(0, summarizationClient.calls);
  }

  /** 情報の要約で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void doesNotCallLlmWhenExtractedContentIsEmpty() {
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    WebPageSummarizerService service =
        new DefaultWebPageSummarizerService(
            new RecordingFetcher(UrlFetch.success("<html></html>")),
            new RecordingExtractor(" "),
            summarizationClient,
            new RecordingConversationHistory());

    SummarizationException error =
        assertThrows(
            SummarizationException.class,
            () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("EMPTY_CONTENT", error.code());
    assertEquals(0, summarizationClient.calls);
  }

  /** 情報の要約で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void recordsUserRequestButNotAssistantSummaryWhenLlmFails() {
    RecordingConversationHistory history = new RecordingConversationHistory();
    RecordingSummarizationClient summarizationClient = new RecordingSummarizationClient("unused");
    summarizationClient.failure = new IllegalStateException("llm down");
    WebPageSummarizerService service =
        new DefaultWebPageSummarizerService(
            new RecordingFetcher(UrlFetch.success("<article>Article body</article>")),
            new RecordingExtractor("Article body"),
            summarizationClient,
            history);

    SummarizationException error =
        assertThrows(
            SummarizationException.class,
            () -> service.summarize(URI.create("https://example.com/article")));

    assertEquals("LLM_ERROR", error.code());
    assertEquals(List.of("user:次のWebページを要約してください: https://example.com/article"), history.messages);
  }

  /** 検証条件を再現するためのRecording情報の取得元。 */
  private static final class RecordingFetcher implements WebContentFetcher {
    /** 検証に使う結果。 */
    private final UrlFetch result;

    /** 検証に使う参照先のURI。 */
    private URI uri;

    /** 検証条件に必要な値を受け取り、RecordingFetcherを用意する。 */
    RecordingFetcher(UrlFetch result) {
      this.result = result;
    }

    /** {@code fetch} の条件を再現するための補助情報を返す。 */
    @Override
    public UrlFetch fetch(URI uri) {
      this.uri = uri;
      return result;
    }
  }

  /** 検証条件を再現するためのRecording情報の抽出。 */
  private static final class RecordingExtractor implements WebPageContentExtractor {
    /** 検証に使う内容。 */
    private final String content;

    /** 検証条件に必要な値を受け取り、RecordingExtractorを用意する。 */
    RecordingExtractor(String content) {
      this.content = content;
    }

    /** {@code extract} の条件を再現するための補助情報を返す。 */
    @Override
    public String extract(String url, String html) {
      assertTrue(html.contains("<"));
      return content;
    }
  }

  /** 検証条件を再現するためのRecordingSummarizationクライアント。 */
  private static final class RecordingSummarizationClient implements SummarizationClient {
    /** 検証に使う要約。 */
    private final String summary;

    /** 検証に使う内容。 */
    private String content;

    /** 検証に使う呼び出し回数。 */
    private int calls;

    /** 検証に使う失敗情報。 */
    private RuntimeException failure;

    /** 検証条件に必要な値を受け取り、RecordingSummarizationClientを用意する。 */
    RecordingSummarizationClient(String summary) {
      this.summary = summary;
    }

    /** {@code summarize} の条件を再現するための補助情報を返す。 */
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

  /** 検証条件を再現するためのRecording会話履歴。 */
  private static final class RecordingConversationHistory implements ConversationHistoryAppender {
    /** 検証に使うメッセージ一覧。 */
    private final List<String> messages = new ArrayList<>();

    /** {@code appendUserMessage} の条件を再現するため、検証用の操作や状態を扱う。 */
    @Override
    public void appendUserMessage(String content) {
      messages.add("user:" + content);
    }

    /** {@code appendAssistantMessage} の条件を再現するため、検証用の操作や状態を扱う。 */
    @Override
    public void appendAssistantMessage(String content) {
      messages.add("assistant:" + content);
    }
  }
}
