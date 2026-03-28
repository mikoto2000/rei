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

  @Tool(name = "webSearch", description = "Web を検索します。外部の最新情報が必要な場合に使います。query と limit を指定できます。")
  List<WebSearchResult> webSearch(String query, Integer limit) throws IOException, InterruptedException {
    return webSearchService.search(query, limit);
  }
}
