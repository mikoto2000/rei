package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Web検索提供元予算の結果と状態の契約を検証するテスト。 */
class WebSearchProviderBudgetTest {
  /** 検証に使うサーバー。 */
  HttpServer server;

  /** 検証に使うこの機能の動作設定。 */
  WebSearchProperties properties;

  /** 検証に使う最初、秒。 */
  AtomicInteger first, second;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void start() throws Exception {
    first = new AtomicInteger();
    second = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    properties = new WebSearchProperties();
    properties.setEnabled(true);
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void close() {
    server.stop(0);
  }

  /** 検証で使用する提供元を、指定された条件に合わせて用意する。 */
  WebSearchProperties.ProviderProperties provider(String path) {
    var result = new WebSearchProperties.ProviderProperties();
    result.setName("duckduckgo");
    result.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + path);
    return result;
  }

  /** {@code html} の条件を再現するため、検証用の操作や状態を扱う。 */
  void html(String path, AtomicInteger calls, String title) {
    server.createContext(
        path,
        exchange -> {
          calls.incrementAndGet();
          byte[] body =
              ("<div class=result><a class=result__a href=https://docs.oracle.com/java>"
                      + title
                      + "</a><span class=result__snippet>"
                      + title
                      + "</span></div>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
  }

  /** Web上の情報の調査で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sufficientFirstProviderAvoidsTheSecondPhysicalCall() throws Exception {
    html("/first", first, "Java streams");
    html("/second", second, "Java streams");
    properties.setProviders(List.of(provider("/first"), provider("/second")));
    assertEquals(
        1, new WebSearchService(properties, new JsonMapper()).search("Java streams", 1).size());
    assertEquals(1, first.get());
    assertEquals(0, second.get());
  }

  /** Web上の情報の調査で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void wrongVersionDoesNotSuppressLaterProvider() throws Exception {
    html("/first", first, "Java 24 reference");
    html("/second", second, "Java 25 reference");
    properties.setProviders(List.of(provider("/first"), provider("/second")));
    var results = new WebSearchService(properties, new JsonMapper()).search("Java 25 reference", 1);
    assertEquals("Java 25 reference", results.getFirst().title());
    assertEquals(1, first.get());
    assertEquals(1, second.get());
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void queryExpansionsSharePhysicalRequestBudget() throws Exception {
    html("/empty", first, "unrelated result");
    properties.setProviders(List.of(provider("/empty")));
    properties.setMaxSearchApiCalls(2);
    WebSearchSelection.search(
        new WebSearchService(properties, new JsonMapper()),
        new WebSearchQueryPlanner(),
        "rare evidence",
        1,
        properties);
    assertEquals(2, first.get());
  }

  /** Web上の情報の調査で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void redirectsCannotBypassRequestQuota() {
    server.createContext(
        "/loop",
        exchange -> {
          first.incrementAndGet();
          exchange.getResponseHeaders().set("Location", "/loop");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    properties.setProviders(List.of(provider("/loop")));
    properties.setMaxSearchApiCalls(2);
    var error =
        assertThrows(
            dev.mikoto2000.rei.http.HttpFetchException.class,
            () -> new WebSearchService(properties, new JsonMapper()).search("rare evidence", 1));
    assertEquals(dev.mikoto2000.rei.http.HttpFetchException.Code.REQUEST_BUDGET, error.code());
    assertEquals(2, first.get());
  }
}
