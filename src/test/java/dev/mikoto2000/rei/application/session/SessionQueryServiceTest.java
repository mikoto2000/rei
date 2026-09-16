package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import static org.assertj.core.api.Assertions.*;

class SessionQueryServiceTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");
  FileSessionRepository store() { return new FileSessionRepository(temp.resolve("sessions.json")); }
  void add(SessionRepository store, String id, String project, Instant time) {
    store.accept(new SessionMetadata(id, project, "title " + id, time, time), () -> {});
  }
  @Test void emptyUnknownAndPersistedDetail() {
    var repo = store(); var query = new SessionQueryService(repo, (id, after, limit) -> java.util.List.of());
    assertThat(query.listSessions(null, null, null).items()).isEmpty();
    assertThat(query.listSessions(null, null, null).nextCursor()).isNull();
    assertThatThrownBy(() -> query.getSession("missing")).isInstanceOf(ResourceNotFoundException.class);
    add(repo, "one", "p", now);
    assertThat(new SessionQueryService(store(), (id, after, count) -> java.util.List.of()).getSession("one"))
        .isEqualTo(new SessionMetadata("one", "p", "title one", now, now));
    assertThat(query.listSessions("unknown", 1, null).items()).isEmpty();
  }
  @Test void stableKeysetPaginationSurvivesInsertAndFiltersProjects() {
    var repo = store(); var query = new SessionQueryService(repo, (id, after, limit) -> java.util.List.of());
    add(repo, "c", "p", now); add(repo, "a", "p", now); add(repo, "b", "q", now);
    add(repo, "d", "p", now.minusSeconds(1));
    var first = query.listSessions(null, 2, null);
    assertThat(first.items()).extracting(SessionMetadata::sessionId).containsExactly("a", "b");
    assertThat(first.nextCursor()).isNotBlank().doesNotContain("sessions.json");
    add(repo, "new", "p", now.plusSeconds(1));
    var second = query.listSessions(null, 2, first.nextCursor());
    assertThat(second.items()).extracting(SessionMetadata::sessionId).containsExactly("c", "d");
    assertThat(second.nextCursor()).isNull();
    var filtered = query.listSessions("p", 2, null);
    assertThat(filtered.items()).extracting(SessionMetadata::sessionId).containsExactly("new", "a");
    assertThat(query.listSessions("p", 2, filtered.nextCursor()).items())
        .extracting(SessionMetadata::sessionId).containsExactly("c", "d");
    assertThatThrownBy(() -> query.listSessions("q", 2, filtered.nextCursor())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void defaultAndMaximumLimit() {
    var repo = store(); var query = new SessionQueryService(repo, (id, after, limit) -> java.util.List.of());
    for (int i = 0; i < 101; i++) add(repo, String.format("%03d", i), "p", now);
    assertThat(query.listSessions(null, null, null).items()).hasSize(50);
    assertThat(query.listSessions(null, 100, null).items()).hasSize(100);
    var page = query.listSessions(null, 1, null);
    assertThat(page.items()).hasSize(1);
    var ids = new ArrayList<String>();
    String cursor = null;
    do {
      var next = query.listSessions(null, 7, cursor);
      next.items().forEach(row -> ids.add(row.sessionId())); cursor = next.nextCursor();
    } while (cursor != null);
    assertThat(ids).hasSize(101).doesNotHaveDuplicates().isSorted();
  }
  @ParameterizedTest @ValueSource(ints = {0, -1, 101})
  void rejectsInvalidLimit(int limit) {
    assertThatThrownBy(() -> new SessionQueryService(store(), (id, after, count) -> java.util.List.of()).listSessions(null, limit, null)).isInstanceOf(IllegalArgumentException.class);
  }
  @ParameterizedTest @ValueSource(strings = {"", "bad", "%%%%", "e30", "AA=="})
  void rejectsInvalidCursor(String cursor) {
    assertThatThrownBy(() -> new SessionQueryService(store(), (id, after, count) -> java.util.List.of()).listSessions(null, 50, cursor)).isInstanceOf(IllegalArgumentException.class);
  }
}
