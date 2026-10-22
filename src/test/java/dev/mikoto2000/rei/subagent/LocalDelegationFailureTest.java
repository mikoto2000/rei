package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import org.junit.jupiter.api.Test;

/** ローカル委譲失敗情報の結果と状態の契約を検証するテスト。 */
class LocalDelegationFailureTest {
  /** SQLite の競合は内部のパスや例外メッセージを含めずに表示する。 */
  @Test
  void sqliteContentionHasSafeStorageGuidance() {
    var failure =
        new RuntimeException(
            new org.sqlite.SQLiteException(
                "private database path", org.sqlite.SQLiteErrorCode.SQLITE_BUSY));
    String description = LocalDelegationFailure.describe(failure, false);
    assertTrue(description.startsWith("database_busy:"));
    assertFalse(description.contains("private database path"));
  }

  /** 補助エージェントへの委譲の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void wrappedPermissionAndTransportFailuresAreClassifiedWithoutPrivateMessages() {
    String permission =
        LocalDelegationFailure.describe(
            new RuntimeException(
                new ToolPermissionException("private-tool", PermissionDecision.DENY)),
            false);
    assertTrue(permission.contains("TOOL_PERMISSION"));
    assertFalse(permission.contains("private-tool"));
    assertTrue(
        LocalDelegationFailure.describe(
                new RuntimeException(new java.io.IOException("private-url")), false)
            .contains("MODEL_TRANSPORT"));
    assertFalse(
        LocalDelegationFailure.describe(new IllegalStateException("password=secret"), true)
            .contains("secret"));
  }

  /** 補助エージェントへの委譲の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void timeoutCancellationAndRateLimitsHaveDistinctCategories() {
    assertTrue(
        LocalDelegationFailure.describe(new java.util.concurrent.TimeoutException(), false)
            .contains("TIMEOUT"));
    assertTrue(
        LocalDelegationFailure.describe(new java.util.concurrent.CancellationException(), false)
            .contains("CANCELLED"));
    var rate =
        org.springframework.web.client.HttpClientErrorException.create(
            org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
            "private body",
            org.springframework.http.HttpHeaders.EMPTY,
            new byte[0],
            null);
    assertTrue(LocalDelegationFailure.describe(rate, false).contains("MODEL_RATE_LIMIT"));
    assertFalse(LocalDelegationFailure.describe(rate, false).contains("private body"));
  }
}
