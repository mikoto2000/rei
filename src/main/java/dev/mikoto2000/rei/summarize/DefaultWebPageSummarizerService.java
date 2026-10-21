package dev.mikoto2000.rei.summarize;

import java.net.URI;
import org.springframework.stereotype.Service;

/** 既定WebページSummarizerのユースケースを実現する。 */
@Service
public class DefaultWebPageSummarizerService implements WebPageSummarizerService {

  /** 本文の文字数上限。 */
  private static final int MAX_CONTENT_CHARS = 60_000;

  /** 情報の取得元。 */
  private final WebContentFetcher fetcher;

  /** 情報の抽出。 */
  private final WebPageContentExtractor extractor;

  /** summarizationクライアント。 */
  private final SummarizationClient summarizationClient;

  /** 会話履歴。 */
  private final ConversationHistoryAppender conversationHistory;

  /** 指定された値や利用先を使って、DefaultWebPageSummarizerServiceを初期化する。 */
  public DefaultWebPageSummarizerService(
      WebContentFetcher fetcher,
      WebPageContentExtractor extractor,
      SummarizationClient summarizationClient,
      ConversationHistoryAppender conversationHistory) {
    this.fetcher = fetcher;
    this.extractor = extractor;
    this.summarizationClient = summarizationClient;
    this.conversationHistory = conversationHistory;
  }

  /** 対象の内容を、要点が分かる形にまとめる。 */
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
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (content.length() > MAX_CONTENT_CHARS) {
      throw new SummarizationException("CONTENT_TOO_LARGE", "本文が長すぎるため要約できません");
    }

    long llmStartedAt = System.nanoTime();
    String summary;
    try {
      summary = summarizationClient.summarize(content);
    } catch (RuntimeException e) {
      throw new SummarizationException(
          "LLM_ERROR", e.getMessage() == null ? "要約生成に失敗しました" : e.getMessage(), e);
    }
    long llmDurationMillis = elapsedMillis(llmStartedAt);
    if (summary == null || summary.isBlank()) {
      throw new SummarizationException("LLM_EMPTY", "要約結果が空でした");
    }

    String strippedSummary = summary.strip();
    conversationHistory.appendAssistantMessage(strippedSummary);
    return new SummaryResult(
        uri,
        strippedSummary,
        new SummaryMetrics(
            fetchDurationMillis,
            extractDurationMillis,
            llmDurationMillis,
            elapsedMillis(startedAt),
            content.length()));
  }

  /** 情報の要約で用いる経過時間ミリ秒数を返す。 */
  private long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }
}
