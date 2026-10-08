package dev.mikoto2000.rei.urlfetch;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;


@Component
public class UrlContentFetchTools {

  private final UrlContentFetchService urlContentFetchService;
  private final dev.mikoto2000.rei.websearch.WebResultBudget resultBudget;
  public UrlContentFetchTools(UrlContentFetchService service) { this(service,dev.mikoto2000.rei.websearch.WebResultBudget.defaults()); }
  @org.springframework.beans.factory.annotation.Autowired
  public UrlContentFetchTools(UrlContentFetchService service,dev.mikoto2000.rei.websearch.WebResultBudget budget) {
    this.urlContentFetchService=service; this.resultBudget=budget;
  }

  @Tool(name = "fetchUrlContent", description = """
      Fetch content from one exact URL using the shared http/https fetcher.
      Use this primitive when the URL is already known.
      For public-web research, prefer webSearchAndRead over manually chaining webSearch and this tool.
      """)
  public UrlContentFetchResult fetchUrlContent(String url,
      @org.springframework.ai.tool.annotation.ToolParam(required = false, description = "Revalidate cached web sources") Boolean forceRefresh,
      org.springframework.ai.chat.model.ToolContext context) {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context);
        var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(Boolean.TRUE.equals(forceRefresh))) {
      resultBudget.requireAvailable(context); return resultBudget.fit(fetchUrlContent(url),context);
    }
  }
  public UrlContentFetchResult fetchUrlContent(String url, org.springframework.ai.chat.model.ToolContext context) {
    return fetchUrlContent(url, null, context);
  }
  public UrlContentFetchResult fetchUrlContent(String url) {
    return urlContentFetchService.fetch(url);
  }
}
