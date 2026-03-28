package dev.mikoto2000.rei.websearch;

import java.io.IOException;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WebSearchTools {

  private final WebSearchService webSearchService;

  @Tool(name = "webSearch", description = "Web を検索します。外部の最新情報が必要な場合に使います。query と limit を指定でき、要約と出典一覧を返します。")
  WebSearchResponse webSearch(String query, Integer limit) throws IOException, InterruptedException {
    return webSearchService.search(query, limit);
  }
}
