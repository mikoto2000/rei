package dev.mikoto2000.rei.conversation;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HistoryStoreReadTest {
  @TempDir Path temp;
  String prior;
  String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
  ConversationLogStore store = new ConversationLogStore();
  @BeforeEach void setup() { prior = System.getProperty("rei.data-dir"); System.setProperty("rei.data-dir", temp.toString()); }
  @AfterEach void cleanup() { if (prior == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", prior); }
  @Test void readsOnlyLastMessagesAndListsProjectMetadataWithoutForeignRows() {
    String id = "project:" + a + ":chat:main";
    for (int i = 0; i < 120; i++) store.append(id, i % 2 == 0 ? "user" : "assistant", "message " + i);
    store.append("project:" + b + ":chat:main", "user", "foreign");
    var recent = store.recentConversation(a, id, 50);
    assertThat(recent).hasSize(50);
    assertThat(recent.getFirst().content()).isEqualTo("message 70");
    assertThat(recent.getLast().content()).isEqualTo("message 119");
    var list = store.listConversations(a, 50, 0);
    assertThat(list).hasSize(1);
    assertThat(list.getFirst().conversationId()).isEqualTo(id);
    assertThat(list.getFirst().messageCount()).isEqualTo(120);
    assertThat(list.getFirst().updatedAt()).isNotNull();
    assertThat(store.listConversations(b, 50, 1)).isEmpty();
    assertThat(store.recentConversation(a, "project:" + b + ":chat:main", 50)).isEmpty();
  }
  @Test void streamingAllPreservesEveryStoredRoleAndIntervention() {
    String id = "project:" + a + ":chat:main";
    for (String role : List.of("system", "user", "assistant", "tool", "user", "assistant")) store.append(id, role, role);
    var entries = new ArrayList<ConversationLogEntry>();
    store.visitProject(a, entries::add);
    assertThat(entries).extracting(ConversationLogEntry::speaker).containsExactly("system", "user", "assistant", "tool", "user", "assistant");
    assertThat(store.listConversations(UUID.randomUUID().toString(), 50, 0)).isEmpty();
  }
}
