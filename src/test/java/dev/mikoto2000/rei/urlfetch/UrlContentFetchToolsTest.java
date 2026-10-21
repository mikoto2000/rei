package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/** URL内容取得ツール一覧の結果と状態の契約を検証するテスト。 */
class UrlContentFetchToolsTest {

  /** 結果を返すことを確認する。 */
  @Test
  void delegatesToServiceAndReturnsResult() {
    UrlContentFetchService service = mock(UrlContentFetchService.class);
    UrlContentFetchResult expected = UrlContentFetchResult.success("page body");
    when(service.fetch("https://example.com")).thenReturn(expected);
    UrlContentFetchTools tools = new UrlContentFetchTools(service);

    UrlContentFetchResult actual = tools.fetchUrlContent("https://example.com");

    assertEquals(expected, actual);
    verify(service).fetch("https://example.com");
  }

  /** URLの内容の参照で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void descriptionRoutesKnownUrlsWithoutManualSearchChaining() throws Exception {
    Tool tool =
        UrlContentFetchTools.class
            .getDeclaredMethod(
                "fetchUrlContent",
                String.class,
                Boolean.class,
                org.springframework.ai.chat.model.ToolContext.class)
            .getAnnotation(Tool.class);

    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("exact URL"));
    org.junit.jupiter.api.Assertions.assertTrue(tool.description().contains("webSearchAndRead"));
  }
}
