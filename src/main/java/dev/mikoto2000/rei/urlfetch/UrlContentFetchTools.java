package dev.mikoto2000.rei.urlfetch;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** URL内容取得をモデルから呼び出せるツールとして提供する。 */
@Component
public class UrlContentFetchTools {

  /** URL内容取得サービス。 */
  private final UrlContentFetchService urlContentFetchService;

  /** 結果予算。 */
  private final dev.mikoto2000.rei.websearch.WebResultBudget resultBudget;

  /** URLの内容の参照を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public UrlContentFetchTools(UrlContentFetchService service) {
    this(service, dev.mikoto2000.rei.websearch.WebResultBudget.defaults());
  }

  /** 指定された値や利用先を使って、UrlContentFetchToolsを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public UrlContentFetchTools(
      UrlContentFetchService service, dev.mikoto2000.rei.websearch.WebResultBudget budget) {
    this.urlContentFetchService = service;
    this.resultBudget = budget;
  }

  /** 外部の情報を利用できるよう、URL内容を取得する。 */
  @Tool(
      name = "fetchUrlContent",
      description =
          """
      Fetch content from one exact URL using the shared http/https fetcher.
      Use this primitive when the URL is already known.
      For public-web research, prefer webSearchAndRead over manually chaining webSearch and this tool.
      """)
  public UrlContentFetchResult fetchUrlContent(
      String url,
      @org.springframework.ai.tool.annotation.ToolParam(
              required = false,
              description = "Revalidate cached web sources")
          Boolean forceRefresh,
      org.springframework.ai.chat.model.ToolContext context) {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context);
        var refresh =
            dev.mikoto2000.rei.http.FetchScope.withForceRefresh(
                Boolean.TRUE.equals(forceRefresh))) {
      resultBudget.requireAvailable(context);
      return resultBudget.fit(fetchUrlContent(url), context);
    }
  }

  /** 外部の情報を利用できるよう、URL内容を取得する。 */
  public UrlContentFetchResult fetchUrlContent(
      String url, org.springframework.ai.chat.model.ToolContext context) {
    return fetchUrlContent(url, null, context);
  }

  /** 外部の情報を利用できるよう、URL内容を取得する。 */
  public UrlContentFetchResult fetchUrlContent(String url) {
    return urlContentFetchService.fetch(url);
  }
}
