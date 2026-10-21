package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Slackエージェント保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SlackAgentRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-11T00:00:00Z");

  /** {@code repo} の条件を再現するための補助情報を返す。 */
  SlackAgentRepository repo() {
    return new SlackAgentRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db")),
        Clock.fixed(now, ZoneOffset.UTC));
  }

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner() {
    return new AgentRunContext("run", "session", dir, "project");
  }

  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  SlackAgentProperties settings() {
    var p = new SlackAgentProperties();
    p.setEnabled(true);
    p.setAllowedWorkspaceIds(Set.of("T1"));
    p.setAllowedChannelIds(Set.of("C1"));
    p.setAgents(Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "")));
    return p;
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  SlackAgentRepository.Request create(SlackAgentRepository r, String call) {
    return r.create(owner(), call, "tenten", "question " + call, "expected", 300, null, settings());
  }

  /** Slackとの連携で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void creationIsIdempotentAndBudgetIsDurable() {
    var r = repo();
    var first = create(r, "call");
    assertEquals(first.id(), create(r, "call").id());
    assertEquals("CREATED", first.state());
    create(r, "two");
    create(r, "three");
    assertThrows(IllegalArgumentException.class, () -> create(repo(), "four"));
    assertThrows(
        IllegalArgumentException.class,
        () -> r.create(owner(), "x", "other", "q", "", 300, null, settings()));
  }

  /** Slackとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sendAndAnswerAreDifferentStatesAndTransitionsAreFenced() {
    var r = repo();
    var q = create(r, "call");
    assertTrue(r.claimSend(q.id()));
    assertFalse(r.claimSend(q.id()));
    r.sent(q.id(), "100.1");
    assertEquals("WAITING", r.get(q.id()).state());
    assertThrows(IllegalStateException.class, () -> r.sent(q.id(), "100.2"));
    assertTrue(r.receive(message("E1", "101.1", "answer"), "U1", settings()));
    assertEquals("WAITING", r.get(q.id()).state());
    assertFalse(r.receive(message("E1", "101.1", "answer"), "U1", settings()));
    assertEquals(1, r.responses(q.id()).size());
  }

  /** {@code message} の条件を再現するための補助情報を返す。 */
  SlackMessage message(String event, String ts, String text) {
    return new SlackMessage(
        event, "T1", "C1", ts, "100.1", "U2", "B2", "bot_message", "message", "channel", text);
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void exactConfiguredMarkerCanCompleteButSilenceCannot() {
    var p = settings();
    p.setAgents(
        Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "FINAL:")));
    var r = repo();
    var q = r.create(owner(), "call", "tenten", "q", "", 300, null, p);
    r.claimSend(q.id());
    r.sent(q.id(), "100.1");
    r.receive(message("ack", "101.1", "working"), "U1", p);
    assertEquals("WAITING", r.get(q.id()).state());
    r.receive(message("final", "102.1", "FINAL: result"), "U1", p);
    assertEquals("COMPLETED", r.get(q.id()).state());
    assertEquals("configured_final_prefix", r.get(q.id()).reason());
  }

  /** Slackとの連携の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void terminalCancellationRejectsLateReplyAndTimeoutRetainsPartial() {
    var r = repo();
    var q = create(r, "call");
    r.claimSend(q.id());
    r.sent(q.id(), "100.1");
    r.receive(message("ack", "101.1", "partial"), "U1", settings());
    r.expire(now.plusSeconds(301));
    assertEquals("TIMED_OUT", r.get(q.id()).state());
    assertEquals("partial", r.responses(q.id()).getFirst().text());
    assertFalse(r.receive(message("late", "102.1", "late"), "U1", settings()));
    var other = create(r, "two");
    r.cancelRun("run");
    assertEquals("CANCELLED", r.get(other.id()).state());
  }

  /** Slackとの連携で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void uncertainSendIsNeverAutomaticallyReplayed() {
    var r = repo();
    var q = create(r, "call");
    r.claimSend(q.id());
    repo().recover();
    assertEquals("UNKNOWN", r.get(q.id()).state());
    assertFalse(r.claimSend(q.id()));
  }

  /** Slackとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void untrustedIdentityAndUnrelatedThreadsAreRejected() {
    var r = repo();
    var q = create(r, "call");
    r.claimSend(q.id());
    r.sent(q.id(), "100.1");
    for (var m :
        List.of(
            new SlackMessage(
                "x",
                "T2",
                "C1",
                "101.1",
                "100.1",
                "U2",
                "B2",
                "bot_message",
                "message",
                "channel",
                "x"),
            new SlackMessage(
                "x",
                "T1",
                "C2",
                "101.1",
                "100.1",
                "U2",
                "B2",
                "bot_message",
                "message",
                "channel",
                "x"),
            new SlackMessage(
                "x",
                "T1",
                "C1",
                "101.1",
                "200.1",
                "U2",
                "B2",
                "bot_message",
                "message",
                "channel",
                "x"),
            new SlackMessage(
                "x",
                "T1",
                "C1",
                "101.1",
                "100.1",
                "U3",
                "B3",
                "bot_message",
                "message",
                "channel",
                "I am B2"),
            new SlackMessage(
                "x",
                "T1",
                "C1",
                "101.1",
                "100.1",
                "U3",
                "B2",
                "bot_message",
                "message",
                "channel",
                "wrong user"),
            new SlackMessage(
                "x",
                "T1",
                "C1",
                "101.1",
                "100.1",
                "U2",
                "B2",
                "message_changed",
                "message",
                "channel",
                "x"))) assertFalse(r.receive(m, "U1", settings()));
    assertFalse(r.receive(message("self", "101.1", "x"), "U2", settings()));
    assertTrue(r.responses(q.id()).isEmpty());
  }

  /** Slackとの連携で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void followUpTurnsAreBoundedAndCannotBorrowAnotherParentsRequest() {
    var r = repo();
    var p = settings();
    p.setMaxRequestsPerRun(16);
    var first = r.create(owner(), "one", "tenten", "one", "", 1, null, p);
    r.expire(now.plusSeconds(2));
    var second = r.create(owner(), "two", "tenten", "two", "", 1, first.id(), p);
    r.expire(now.plusSeconds(2));
    var third = r.create(owner(), "three", "tenten", "three", "", 1, second.id(), p);
    r.expire(now.plusSeconds(2));
    assertThrows(
        IllegalArgumentException.class,
        () -> r.create(owner(), "four", "tenten", "four", "", 1, third.id(), p));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            r.create(
                new AgentRunContext("other", "session", dir, "project"),
                "borrow",
                "tenten",
                "borrow",
                "",
                1,
                first.id(),
                p));
    assertEquals(3, r.forRun("run").size());
  }
}
