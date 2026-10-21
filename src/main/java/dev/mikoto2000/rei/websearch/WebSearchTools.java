package dev.mikoto2000.rei.websearch;

import java.io.IOException;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 検索結果の一覧取得と本文付きの調査を、モデルが選べるツールとして提供する。 */
@Component
public class WebSearchTools {

  /** 本文を取得せず、検索結果のタイトル・URL・抜粋を集める窓口。 */
  private final WebSearchService webSearchService;

  /** 検索と上位ページの本文取得を一度に行う調査の窓口。 */
  private final WebSearchAndReadService webSearchAndReadService;

  /** モデルへ返す検索結果を、残っている文脈予算に収めるための制御。 */
  private final WebResultBudget resultBudget;

  /** Web上の情報の調査を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public WebSearchTools(WebSearchService search, WebSearchAndReadService read) {
    this(search, read, WebResultBudget.defaults());
  }

  /** 指定された値や利用先を使って、WebSearchToolsを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WebSearchTools(
      WebSearchService search, WebSearchAndReadService read, WebResultBudget budget) {
    this.webSearchService = search;
    this.webSearchAndReadService = read;
    this.resultBudget = budget;
  }

  /** タイトル・URL・抜粋を検索結果として返し、本文取得を必要としない調査に利用する。 */
  @Tool(
      name = "webSearch",
      description =
          """
      Search the public web and return result metadata such as titles, URLs, and snippets.
      Use this primitive when result metadata or candidate URLs are sufficient.
      For normal public-web research that needs page content, prefer webSearchAndRead.
      """)
  List<WebSearchResult> webSearch(
      String query,
      Integer limit,
      @org.springframework.ai.tool.annotation.ToolParam(
              required = false,
              description = "Revalidate cached web sources")
          Boolean forceRefresh,
      org.springframework.ai.chat.model.ToolContext context)
      throws IOException, InterruptedException {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context);
        var refresh =
            dev.mikoto2000.rei.http.FetchScope.withForceRefresh(
                Boolean.TRUE.equals(forceRefresh))) {
      resultBudget.requireAvailable(context);
      return resultBudget.fitMetadata(webSearch(query, limit), context);
    }
  }

  /** タイトル・URL・抜粋を検索結果として返し、本文取得を必要としない調査に利用する。 */
  List<WebSearchResult> webSearch(
      String query, Integer limit, org.springframework.ai.chat.model.ToolContext context)
      throws IOException, InterruptedException {
    return webSearch(query, limit, null, context);
  }

  /** タイトル・URL・抜粋を検索結果として返し、本文取得を必要としない調査に利用する。 */
  List<WebSearchResult> webSearch(String query, Integer limit)
      throws IOException, InterruptedException {
    IO.println(String.format("Web を検索するよ。limit=%s", limit));
    return webSearchService.search(query, limit);
  }

  /** 公開Webの検索と上位ページの本文取得をまとめて行い、調査の根拠を返す。 */
  @Tool(
      name = "webSearchAndRead",
      description =
          """
      Search the public web and fetch readable content from the top search results in a single call.
      Use this as the default tool when researching information on the public web.
      Prefer this over calling webSearch followed by fetchUrlContent manually.
      Use webSearch directly only when search-result metadata or URL candidates are needed without page contents.
      Use fetchUrlContent directly when the exact URL to read is already known.
      Pass arguments inside request. query is required (1..2048 characters).
      maxResults must be an integer from 1 to the configured maximum (normally 5); omit it to use that maximum.
      readTop must be an integer from 0 to maxResults; it defaults to min(3, maxResults).
      Set readTop = 0 to return search metadata without fetching page contents.
      Individual page fetch failures are returned per result and do not fail the whole search.
      """)
  public WebSearchAndReadResponse webSearchAndRead(
      WebSearchAndReadRequest request, org.springframework.ai.chat.model.ToolContext context)
      throws IOException, InterruptedException {
    try (var scope = dev.mikoto2000.rei.http.FetchScope.enter(context)) {
      resultBudget.requireAvailable(context);
      return resultBudget.fit(webSearchAndRead(request), context);
    }
  }

  /** 公開Webの検索と上位ページの本文取得をまとめて行い、調査の根拠を返す。 */
  public WebSearchAndReadResponse webSearchAndRead(WebSearchAndReadRequest request)
      throws IOException, InterruptedException {
    return webSearchAndReadService.searchAndRead(request);
  }
}
