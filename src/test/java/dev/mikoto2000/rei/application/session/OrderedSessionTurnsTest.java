package dev.mikoto2000.rei.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ordered会話やり取り一覧の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class OrderedSessionTurnsTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 会話の継続管理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void equalOrRegressingClockCannotReverseExecutionOrderAcrossRestart() {
    var turns = new ConversationTurnStore(temp);
    var now = Instant.parse("2026-09-17T00:00:00Z");
    turns.startOrdered(new AgentRunContext("z", "session", temp), "first", now);
    turns = new ConversationTurnStore(temp);
    turns.startOrdered(new AgentRunContext("a", "session", temp), "second", now);
    turns.startOrdered(new AgentRunContext("0", "session", temp), "third", now.minusSeconds(5));
    assertThat(turns.findTurns("session", null, 100))
        .extracting(SessionTurn::runId)
        .containsExactly("z", "a", "0");
  }
}
