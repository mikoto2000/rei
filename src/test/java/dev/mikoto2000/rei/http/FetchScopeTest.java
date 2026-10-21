package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/** 取得対象範囲の結果と状態の契約を検証するテスト。 */
class FetchScopeTest {
  /** 外部情報の取得の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void toolContextCancellationAndNestedScopesAreRestored() {
    var run = mock(RunExecutionContext.class);
    doThrow(new CancellationException()).when(run).checkActive();
    var outer = FetchScope.current();
    try (var scope = FetchScope.enter(new ToolContext(Map.of(RunExecutionContext.KEY, run)))) {
      assertThrows(CancellationException.class, () -> FetchScope.current().check());
      try (var nested = FetchScope.enter(FetchOperation.active())) {
        assertDoesNotThrow(() -> FetchScope.current().check());
      }
      assertThrows(CancellationException.class, () -> FetchScope.current().check());
    }
    assertEquals(outer.deadlineNanos(), FetchScope.current().deadlineNanos());
    assertDoesNotThrow(() -> FetchScope.current().check());
  }

  /** ツールデータ形式一覧について、引数を保持することを確認する。 */
  @Test
  void toolSchemasPreserveArgumentsAndHideExecutionContext() {
    var tools =
        new Object[] {
          new dev.mikoto2000.rei.websearch.WebSearchTools(null, null),
          new dev.mikoto2000.rei.urlfetch.UrlContentFetchTools(null),
          new dev.mikoto2000.rei.search.SearchTools(null)
        };
    var callbacks =
        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    assertEquals(4, callbacks.length);
    for (var callback : callbacks) {
      assertFalse(callback.getToolDefinition().inputSchema().contains("ToolContext"));
      assertFalse(callback.getToolDefinition().inputSchema().contains("\"context\""));
    }
  }
}
