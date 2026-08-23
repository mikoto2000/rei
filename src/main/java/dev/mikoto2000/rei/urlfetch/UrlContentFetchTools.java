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
  public UrlContentFetchResult fetchUrlContent(String url) {
    return urlContentFetchService.fetch(url);
  }
}
