package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import static org.assertj.core.api.Assertions.*;

class SessionTurnsTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");
  @Test void persistedTurnsUseChronologicalStablePagesAndPreserveRunIdentity() {
    var repo = new FileSessionRepository(temp.resolve("sessions.json"));
    repo.accept(new SessionMetadata("chat:one", "p", "title", now, now), () -> {});
    var turns = new ConversationTurnStore(temp);
    var query = new SessionQueryService(repo, turns);
    assertThat(query.listTurns("chat:one", null, null).items()).isEmpty();
    for (String id : new String[]{"b", "a", "c"}) {
      var context = new AgentRunContext(id, "chat:one", temp);
      turns.start(context, "question " + id, id.equals("c") ? now.plusSeconds(1) : now);
      turns.finish(context, ConversationTurnStore.Status.COMPLETED, "answer " + id);
    }
    query = new SessionQueryService(new FileSessionRepository(temp.resolve("sessions.json")), new ConversationTurnStore(temp));
    var page = query.listTurns("chat:one", 1, null);
    assertThat(page.items()).containsExactly(new SessionTurn("a", "question a", "answer a", now));
    page = query.listTurns("chat:one", 1, page.nextCursor());
    assertThat(page.items()).extracting(SessionTurn::runId).containsExactly("b");
    page = query.listTurns("chat:one", 1, page.nextCursor());
    assertThat(page.items()).extracting(SessionTurn::runId).containsExactly("c");
    assertThat(page.nextCursor()).isNull();
  }

  @Test void unknownSessionAndInvalidPaginationAreRejected() {
    var repo = new FileSessionRepository(temp.resolve("sessions.json"));
    var query = new SessionQueryService(repo, new ConversationTurnStore(temp));
    assertThatThrownBy(() -> query.listTurns("missing", 50, null)).isInstanceOf(ResourceNotFoundException.class);
    repo.accept(new SessionMetadata("one", "p", "title", now, now), () -> {});
    for (int limit : new int[]{0, -1, 101})
      assertThatThrownBy(() -> query.listTurns("one", limit, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> query.listTurns("one", 50, "invalid")).isInstanceOf(IllegalArgumentException.class);
    String foreign = new CursorCodec().encode("turns:other", new CursorKey(now, "run"));
    assertThatThrownBy(() -> query.listTurns("one", 50, foreign)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void runningTurnHasNoFabricatedAssistantAndLegacyTimestampIsNotInvented() throws Exception {
    var repo = new FileSessionRepository(temp.resolve("sessions.json"));
    repo.accept(new SessionMetadata("one", "p", "title", now, now), () -> {});
    var turns = new ConversationTurnStore(temp);
    var context = new AgentRunContext("run", "one", temp);
    turns.start(context, "question", now);
    var page = new SessionQueryService(repo, turns).listTurns("one", 100, null);
    assertThat(page.items()).containsExactly(new SessionTurn("run", "question", null, now));
    var file = temp.resolve("state/turns").resolve(java.util.UUID.nameUUIDFromBytes("one".getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
    Files.writeString(file, "[{\"runId\":\"old\",\"request\":\"old request\",\"status\":\"COMPLETED\"}]");
    var legacy = new ConversationTurnStore(temp);
    assertThat(legacy.read("one").getFirst().createdAt()).isNull();
    assertThat(new SessionQueryService(repo, legacy).listTurns("one", 50, null).items()).isEmpty();
  }

  @Test void defaultMaximumAndAppendBetweenPagesKeepExistingTurns() {
    var repo = new FileSessionRepository(temp.resolve("sessions.json"));
    repo.accept(new SessionMetadata("one", "p", "title", now, now), () -> {});
    var turns = ConversationTurnStore.inMemory();
    for (int i = 0; i < 100; i++) turns.start(new AgentRunContext(String.format("%03d", i), "one", temp), "q", now);
    var query = new SessionQueryService(repo, turns);
    var first = query.listTurns("one", null, null);
    assertThat(first.items()).hasSize(50);
    assertThat(query.listTurns("one", 100, null).items()).hasSize(100);
    turns.start(new AgentRunContext("new", "one", temp), "new question", now.plusSeconds(1));
    var second = query.listTurns("one", 100, first.nextCursor());
    assertThat(second.items()).hasSize(51);
    assertThat(second.items().getFirst().runId()).isEqualTo("050");
    assertThat(second.items().getLast().runId()).isEqualTo("new");
    assertThat(second.nextCursor()).isNull();
  }
}
