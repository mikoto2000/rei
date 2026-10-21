package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 加工前の結果ページ安全性の結果と状態の契約を検証するテスト。 */
class RawResultPageSafetyTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデルへ渡す文脈の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void requestedLargePageShrinksAndExposesExactContinuation() throws Exception {
    var store = new RawToolResultStore(dir);
    String raw = "教材🙂\\\"\r\n".repeat(4000);
    String ref = store.save("chat", "run", "reader", "call", raw);
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      var page = new RawToolResultTools(store).readRawToolResult(ref, 0, 16000);
      assertThat(TokenEstimator.conservative().text(page)).isLessThanOrEqualTo(2048);
      assertThat(page).contains("nextOffset:", "hasMore: true");
    }
  }

  /** モデルへ渡す文脈の管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void lowSurrogateCursorIsRejected() {
    var store = new RawToolResultStore(dir);
    String ref = store.save("chat", "run", "reader", "call", "a🙂b");
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      assertThatThrownBy(() -> new RawToolResultTools(store).readRawToolResult(ref, 2, 1))
          .hasMessageContaining("INVALID_CURSOR");
    }
  }
}
