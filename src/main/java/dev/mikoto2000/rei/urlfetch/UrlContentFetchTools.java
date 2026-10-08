package dev.mikoto2000.rei.urlfetch;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UrlContentFetchTools {

  private final UrlContentFetchService urlContentFetchService;

  @Tool(name = "fetchUrlContent", description = """
      Fetch content from one exact URL using the shared http/https fetcher.
      Use this primitive when the URL is already known.
      For public-web research, prefer webSearchAndRead over manually chaining webSearch and this tool.
      """)
  public UrlContentFetchResult fetchUrlContent(String url,
      @org.springframework.ai.tool.annotation.ToolParam(required = false, description = "Revalidate cached web sources") Boolean forceRefresh,
      org.springframework.ai.chat.model.ToolContext context) {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context);
        var refresh = dev.mikoto2000.rei.http.FetchScope.withForceRefresh(Boolean.TRUE.equals(forceRefresh))) { return fetchUrlContent(url); }
  }
  public UrlContentFetchResult fetchUrlContent(String url, org.springframework.ai.chat.model.ToolContext context) {
    return fetchUrlContent(url, null, context);
  }
  public UrlContentFetchResult fetchUrlContent(String url) {
    return urlContentFetchService.fetch(url);
  }
}
