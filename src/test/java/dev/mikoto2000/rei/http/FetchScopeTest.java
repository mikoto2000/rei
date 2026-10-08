package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;

class FetchScopeTest {
  @Test void toolContextCancellationAndNestedScopesAreRestored() {
    var run = mock(RunExecutionContext.class);
    doThrow(new CancellationException()).when(run).checkActive();
    var outer = FetchScope.current();
    try (var scope = FetchScope.enter(new ToolContext(Map.of(RunExecutionContext.KEY, run)))) {
      assertThrows(CancellationException.class, () -> FetchScope.current().check());
      try (var nested = FetchScope.enter(FetchOperation.active())) { assertDoesNotThrow(() -> FetchScope.current().check()); }
      assertThrows(CancellationException.class, () -> FetchScope.current().check());
    }
    assertEquals(outer.deadlineNanos(), FetchScope.current().deadlineNanos());
    assertDoesNotThrow(() -> FetchScope.current().check());
  }
  @Test void toolSchemasPreserveArgumentsAndHideExecutionContext() {
    var tools = new Object[] {new dev.mikoto2000.rei.websearch.WebSearchTools(null, null),
        new dev.mikoto2000.rei.urlfetch.UrlContentFetchTools(null), new dev.mikoto2000.rei.search.SearchTools(null)};
    var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    assertEquals(4, callbacks.length);
    for (var callback : callbacks) {
      assertFalse(callback.getToolDefinition().inputSchema().contains("ToolContext"));
      assertFalse(callback.getToolDefinition().inputSchema().contains("\"context\""));
    }
  }
}
