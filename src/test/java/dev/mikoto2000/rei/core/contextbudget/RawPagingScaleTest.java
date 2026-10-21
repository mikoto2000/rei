package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 加工前のPaging倍率の結果と状態の契約を検証するテスト。 */
class RawPagingScaleTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 元の本文一度だけを書き込むことを確認する。 */
  @Test
  void hundredsOfPagesReadAndWriteOriginalBodyOnceAndNeverReuseAcrossScopes() {
    var store = new RawToolResultStore(dir);
    String source = "原文🙂\r\n".repeat(8000);
    String ref = store.save("chat", "run", "reader", "call", source);
    var owner = new AgentRunContext("run", "chat", dir, "project");
    int calls = 0, offset = 0;
    var text = new StringBuilder();
    try (var scope = AgentRunScope.open(owner)) {
      var pages = new RawToolResultTools(store, TokenEstimator.conservative(), 256);
      while (offset < source.length()) {
        String wire = pages.readRawToolResult(ref, offset, 16000);
        String[] split = wire.split("\n", 8);
        int next = Integer.parseInt(split[5].substring(12));
        assertThat(next).isGreaterThan(offset);
        text.append(split[7]);
        offset = next;
        calls++;
      }
      assertThat(store.bodyReadCount()).isEqualTo(1);
      assertThat(store.bodyWriteCount()).isEqualTo(1);
      assertThatThrownBy(() -> store.read("other", ref))
          .hasMessageContaining("RAW_RESULT_ACCESS_DENIED");
    }
    assertThat(calls).isGreaterThan(100);
    assertThat(text.toString()).isEqualTo(source);
    store.clearRun("run");
    try (var scope = AgentRunScope.open(owner)) {
      store.read("chat", ref);
      assertThat(store.bodyReadCount()).isEqualTo(2);
    }
  }
}
