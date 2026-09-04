package dev.mikoto2000.rei.summarize;

import java.net.URI;

import org.springframework.stereotype.Service;

@Service
public class DefaultWebPageSummarizerService implements WebPageSummarizerService {

  private static final int MAX_CONTENT_CHARS = 60_000;

  private final WebContentFetcher fetcher;
  private final WebPageContentExtractor extractor;
  private final SummarizationClient summarizationClient;
  private final ConversationHistoryAppender conversationHistory;

  public DefaultWebPageSummarizerService(WebContentFetcher fetcher, WebPageContentExtractor extractor,
      SummarizationClient summarizationClient, ConversationHistoryAppender conversationHistory) {
    this.fetcher = fetcher;
    this.extractor = extractor;
    this.summarizationClient = summarizationClient;
    this.conversationHistory = conversationHistory;
  }

  @Override
  public SummaryResult summarize(URI uri) {
    long startedAt = System.nanoTime();
    String requestMessage = "次のWebページを要約してください: " + uri;
    conversationHistory.appendUserMessage(requestMessage);

    long fetchStartedAt = System.nanoTime();
    UrlFetch fetched = fetcher.fetch(uri);
    long fetchDurationMillis = elapsedMillis(fetchStartedAt);
    if (!fetched.success()) {
      throw new SummarizationException(fetched.errorType(), fetched.errorMessage());
    }

    long extractStartedAt = System.nanoTime();
    String content = extractor.extract(uri.toString(), fetched.content());
    long extractDurationMillis = elapsedMillis(extractStartedAt);
    if (content == null || content.isBlank()) {
      throw new SummarizationException("EMPTY_CONTENT", "要約可能な本文を抽出できませんでした");
    }
    if (content.length() > MAX_CONTENT_CHARS) {
      throw new SummarizationException("CONTENT_TOO_LARGE", "本文が長すぎるため要約できません");
    }

    long llmStartedAt = System.nanoTime();
    String summary;
    try {
      summary = summarizationClient.summarize(content);
    } catch (RuntimeException e) {
      throw new SummarizationException("LLM_ERROR",
          e.getMessage() == null ? "要約生成に失敗しました" : e.getMessage(), e);
    }
    long llmDurationMillis = elapsedMillis(llmStartedAt);
    if (summary == null || summary.isBlank()) {
      throw new SummarizationException("LLM_EMPTY", "要約結果が空でした");
    }

    String strippedSummary = summary.strip();
    conversationHistory.appendAssistantMessage(strippedSummary);
    return new SummaryResult(uri, strippedSummary, new SummaryMetrics(
        fetchDurationMillis,
        extractDurationMillis,
        llmDurationMillis,
        elapsedMillis(startedAt),
        content.length()));
  }

  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }
}
