package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Slack保存先の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackStoreTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** {@code message} の条件を再現するための補助情報を返す。 */
  SlackMessage message(
      String id, String type, String team, String channel, String ts, String thread) {
    return new SlackMessage(id, team, channel, ts, thread, "U", null, null, type, "im", "hello");
  }

  /** Slackとの連携で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void deduplicatesEventAndMessageAcrossRestart() {
    var s = new SlackStore(root);
    var m = message("E", "message", "T", "D", "1", null);
    assertTrue(s.accept(m));
    assertFalse(s.accept(m));
    assertFalse(new SlackStore(root).accept(message("E2", "app_mention", "T", "D", "1", null)));
    assertEquals(1, new SlackStore(root).pending().size());
  }

  /** Slackとの連携で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void threadMappingsAreScopedAndPersistent() {
    var s = new SlackStore(root);
    var m = message("E", "message", "T", "D", "1", null);
    s.bind(m, "P", "S");
    assertEquals("S", new SlackStore(root).thread(m).orElseThrow().session());
    assertTrue(s.thread(message("E2", "message", "T2", "D", "1", null)).isEmpty());
    assertTrue(s.thread(message("E3", "message", "T", "C", "1", null)).isEmpty());
    assertTrue(s.thread(message("E4", "message", "T", "D", "2", null)).isEmpty());
    assertEquals(
        "S", s.thread(message("E5", "message", "T", "D", "3", "1")).orElseThrow().session());
  }

  /** 不確実な送信するを保持することを確認する。 */
  @Test
  void outboxRetriesOnlyDefiniteFailuresAndPreservesUncertainSends() {
    var s = new SlackStore(root);
    var m = message("E", "message", "T", "D", "1", null);
    s.reply("R", m, "hello");
    assertEquals(1, s.outbox(0).size());
    var row = s.outbox(0).getFirst();
    assertTrue(s.claimSend(row.id()));
    assertFalse(s.claimSend(row.id()));
    assertEquals(0, new SlackStore(root).outbox(Long.MAX_VALUE).size());
    s.sent(row.id(), "9");
    assertEquals(0, s.outbox(Long.MAX_VALUE).size());
    s.reply("R", m, "hello");
    assertEquals(0, s.outbox(Long.MAX_VALUE).size());
    s.reply("R2", m, "retry");
    row = s.outbox(0).getFirst();
    s.claimSend(row.id());
    s.retry(row.id(), 1000);
    assertEquals(0, s.outbox(999).size());
    assertEquals(1, s.outbox(1000).size());
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void rateLimitedChannelCannotBeBypassedByLaterReplies() {
    var s = new SlackStore(root);
    var m = message("E", "message", "T", "D", "1", null);
    s.reply("one", m, "one");
    s.reply("two", m, "two");
    s.claimSend("one");
    s.retry("one", 1000);
    assertEquals(0, s.outbox(999).size());
    assertEquals("one", s.outbox(1000).getFirst().id());
  }

  /** 会話返信所有権を維持することを確認する。 */
  @Test
  void invitedExistingThreadKeepsSessionReplyOwnershipAcrossRestart() {
    var s = new SlackStore(root);
    var m = message("invite", "app_mention", "T", "C", "2", "1");
    s.accept(m);
    var binding = s.bind(m, "P", "S");
    s.prepare(m, "R", binding);
    assertEquals(m, new SlackStore(root).forSession("P", "S").orElseThrow());
    assertTrue(s.forSession("other", "S").isEmpty());
  }
}
