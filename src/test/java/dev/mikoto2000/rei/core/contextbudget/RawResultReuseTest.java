package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 加工前の結果Reuseの結果と状態の契約を検証するテスト。 */
class RawResultReuseTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデルへ渡す文脈の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void identicalBodiesAreStoredOnceButCallIdentityIsPreserved() {
    var store = new RawToolResultStore(dir);
    String first = store.save("chat", "run", "reader", "one", "本文🙂".repeat(200));
    String second = store.save("chat", "run", "reader", "two", "本文🙂".repeat(200));
    assertThat(first).isNotEqualTo(second);
    assertThat(store.bodyWriteCount()).isEqualTo(1);
    assertThat(store.read("chat", first).toolCallId()).isEqualTo("one");
    assertThat(store.read("chat", second).toolCallId()).isEqualTo("two");
  }

  /** 繰り返されたページ一覧について、変更できない本文を再利用することを確認する。 */
  @Test
  void repeatedPagesReuseImmutableBodyOnlyWithinTheCapturedRun() {
    var store = new RawToolResultStore(dir);
    String ref = store.save("chat", "run", "reader", "one", "本文🙂".repeat(200));
    Object first;
    try (var scope = AgentRunScope.open(new AgentRunContext("run", "chat", dir))) {
      first = store.read("chat", ref);
      assertThat(store.read("chat", ref)).isSameAs(first);
      assertThat(store.bodyReadCount()).isEqualTo(1);
    }
    try (var scope = AgentRunScope.open(new AgentRunContext("another", "chat", dir))) {
      assertThat(store.read("chat", ref)).isNotSameAs(first);
      assertThat(store.bodyReadCount()).isEqualTo(2);
    }
  }

  /** モデルへ渡す文脈の管理で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void capacityRejectsBeforeCreatingAnOversizedBody() {
    var store = new RawToolResultStore(dir, null, 100, 2048, 2048);
    assertThatThrownBy(() -> store.save("chat", "run", "reader", "one", "x".repeat(1000)))
        .hasMessageContaining("RAW_RESULT_CAPACITY_EXCEEDED");
    assertThat(store.bodyWriteCount()).isZero();
  }
}
