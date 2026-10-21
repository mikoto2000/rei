package dev.mikoto2000.rei.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 取得再確認対象範囲の結果と状態の契約を検証するテスト。 */
class FetchRefreshScopeTest {
  /** 外部情報の取得で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void refreshSurvivesNestedScopesAndRestoresAfterClose() {
    assertFalse(FetchScope.forceRefresh());
    try (var refresh = FetchScope.withForceRefresh(true)) {
      try (var nested = FetchScope.enter(FetchOperation.active());
          var weaker = FetchScope.withForceRefresh(false)) {
        assertTrue(FetchScope.forceRefresh());
      }
      assertTrue(FetchScope.forceRefresh());
    }
    assertFalse(FetchScope.forceRefresh());
  }
}
