package dev.mikoto2000.rei.application.session;

import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.assertj.core.api.Assertions.*;

class OrderedSessionTurnsTest {
  @TempDir Path temp;
  @Test void equalOrRegressingClockCannotReverseExecutionOrderAcrossRestart() {
    var turns = new ConversationTurnStore(temp);
    var now = Instant.parse("2026-09-17T00:00:00Z");
    turns.startOrdered(new AgentRunContext("z", "session", temp), "first", now);
    turns = new ConversationTurnStore(temp);
    turns.startOrdered(new AgentRunContext("a", "session", temp), "second", now);
    turns.startOrdered(new AgentRunContext("0", "session", temp), "third", now.minusSeconds(5));
    assertThat(turns.findTurns("session", null, 100)).extracting(SessionTurn::runId).containsExactly("z", "a", "0");
  }
}
