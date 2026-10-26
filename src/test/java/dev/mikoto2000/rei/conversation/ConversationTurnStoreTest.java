package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会話一回のやり取り保存先の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ConversationTurnStoreTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 会話履歴と受付記録の管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void responseAndOriginalTimestampSurviveRestartAndTerminalUpdates() {
    var store = new ConversationTurnStore(temp);
    var context = new AgentRunContext("run", "chat:one", temp);
    var created = java.time.Instant.parse("2026-09-16T08:00:00Z");
    store.start(context, "question", created);
    store.finish(context, ConversationTurnStore.Status.COMPLETED, "answer");
    var restored = new ConversationTurnStore(temp);
    var turn = restored.read(context.conversationId()).getFirst();
    assertThat(turn.createdAt()).isEqualTo(created);
    assertThat(turn.assistantMessage()).isEqualTo("answer");
    assertThat(turn.runId()).isEqualTo("run");
    restored.finish(context, ConversationTurnStore.Status.FAILED, "incorrect");
    assertThat(restored.read(context.conversationId())).containsExactly(turn);
  }

  /** 会話履歴と受付記録の管理の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationSurvivesRestartAndUnrelatedCompletedTurnsWithoutCrossConversationLeak() {
    var store = new ConversationTurnStore(temp);
    var a = new AgentRunContext("a", "chat:main", temp);
    store.start(a, "write network.md");
    store.finish(a, ConversationTurnStore.Status.CANCELLED);
    var greeting = new AgentRunContext("b", "chat:main", temp);
    store.start(greeting, "hello");
    store.finish(greeting, ConversationTurnStore.Status.COMPLETED);
    var restored = new ConversationTurnStore(temp);
    assertThat(restored.read("chat:main"))
        .extracting(ConversationTurnStore.Turn::status)
        .containsExactly(
            ConversationTurnStore.Status.CANCELLED, ConversationTurnStore.Status.COMPLETED);
    assertThat(restored.cancelledContext("chat:main"))
        .contains("archived", "getConversationHistory")
        .doesNotContain("write network.md");
    assertThat(restored.cancelledContext("chat:other")).isEmpty();
    restored.finish(a, ConversationTurnStore.Status.FAILED);
    assertThat(restored.read("chat:main").getFirst().status())
        .isEqualTo(ConversationTurnStore.Status.CANCELLED);
  }
}
