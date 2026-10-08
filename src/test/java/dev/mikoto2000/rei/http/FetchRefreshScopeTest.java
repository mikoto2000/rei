package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FetchRefreshScopeTest {
  @Test void refreshSurvivesNestedScopesAndRestoresAfterClose() {
    assertFalse(FetchScope.forceRefresh());
    try (var refresh = FetchScope.withForceRefresh(true)) {
      try (var nested = FetchScope.enter(FetchOperation.active()); var weaker = FetchScope.withForceRefresh(false)) {
        assertTrue(FetchScope.forceRefresh());
      }
      assertTrue(FetchScope.forceRefresh());
    }
    assertFalse(FetchScope.forceRefresh());
  }
}
