package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 履歴保存先読み取りの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class HistoryStoreReadTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** 検証に使うprior。 */
  String prior;

  /** 検証に使うa、b。 */
  String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();

  /** 検証に使う保存先。 */
  ConversationLogStore store = new ConversationLogStore();

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    prior = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.toString());
  }

  /** 次の検証へ状態を持ち越さないよう、利用した資源と保存先を片付ける。 */
  @AfterEach
  void cleanup() {
    if (prior == null) System.clearProperty("rei.data-dir");
    else System.setProperty("rei.data-dir", prior);
  }

  /** 会話履歴と受付記録の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void readsOnlyLastMessagesAndListsProjectMetadataWithoutForeignRows() {
    String id = "project:" + a + ":chat:main";
    for (int i = 0; i < 120; i++)
      store.append(id, i % 2 == 0 ? "user" : "assistant", "message " + i);
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

  /** 逐次応答全対象について、各対象保存済み役割を保持することを確認する。 */
  @Test
  void streamingAllPreservesEveryStoredRoleAndIntervention() {
    String id = "project:" + a + ":chat:main";
    for (String role : List.of("system", "user", "assistant", "tool", "user", "assistant"))
      store.append(id, role, role);
    var entries = new ArrayList<ConversationLogEntry>();
    store.visitProject(a, entries::add);
    assertThat(entries)
        .extracting(ConversationLogEntry::speaker)
        .containsExactly("system", "user", "assistant", "tool", "user", "assistant");
    assertThat(store.listConversations(UUID.randomUUID().toString(), 50, 0)).isEmpty();
  }
}
