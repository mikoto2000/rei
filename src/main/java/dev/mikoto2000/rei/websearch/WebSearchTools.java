package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WebSearchTools {

  private final WebSearchService webSearchService;
  private final WebSearchAndReadService webSearchAndReadService;

  @Tool(name = "webSearch", description = "Web を検索します。外部の最新情報が必要な場合に使います。query と limit を指定できます。")
  List<WebSearchResult> webSearch(String query, Integer limit) throws IOException, InterruptedException {
    IO.println(String.format("Web を検索するよ。query=%s、limit=%s", query, limit));
    return webSearchService.search(query, limit);
  }

  @Tool(name = "webSearchAndRead", description = """
      Search the public web and fetch readable content from the top search results in a single call.
      Use this as the default tool when researching information on the public web.
      Prefer this over calling webSearch followed by fetchUrlContent manually.
      Use webSearch directly only when search-result metadata or URL candidates are needed without page contents.
      Use fetchUrlContent directly when the exact URL to read is already known.
      query is required. maxResults defaults to the configured maximum (normally 5). readTop defaults to 3.
      Set readTop = 0 to return search metadata without fetching page contents.
      Individual page fetch failures are returned per result and do not fail the whole search.
      """)
  public WebSearchAndReadResponse webSearchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    return webSearchAndReadService.searchAndRead(request);
  }
}
