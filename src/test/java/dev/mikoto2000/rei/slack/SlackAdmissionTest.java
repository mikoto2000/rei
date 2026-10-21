package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Slack受付の結果と状態の契約を検証するテスト。 */
class SlackAdmissionTest {
  /** 検証で使用する設定を、指定された条件に合わせて用意する。 */
  private SlackProperties settings() {
    var p = new SlackProperties();
    p.setAllowedWorkspaceIds(Set.of("T"));
    p.setAllowedUserIds(Set.of("U"));
    p.setAllowedChannelIds(Set.of("C", "D"));
    return p;
  }

  /** {@code message} の条件を再現するための補助情報を返す。 */
  private SlackMessage message(
      String type,
      String channel,
      String thread,
      String user,
      String bot,
      String subtype,
      String text) {
    return new SlackMessage(
        "E",
        "T",
        channel,
        "1",
        thread,
        user,
        bot,
        subtype,
        type,
        channel.equals("D") ? "im" : "channel",
        text);
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void dmAndMentionStartButUnrelatedThreadDoesNot() {
    var p = settings();
    assertTrue(
        SlackAdmission.accept(
            p, "B", message("message", "D", null, "U", null, null, "hello"), false));
    assertTrue(
        SlackAdmission.accept(
            p, "B", message("app_mention", "C", null, "U", null, null, "<@B> hello"), false));
    assertFalse(
        SlackAdmission.accept(
            p, "B", message("message", "C", "1", "U", null, null, "hello"), false));
    assertTrue(
        SlackAdmission.accept(
            p, "B", message("message", "C", "1", "U", null, null, "hello"), true));
  }

  /** Slackとの連携で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failClosedAndIgnoreBotsEditsAndDeletes() {
    var p = settings();
    for (var m :
        new SlackMessage[] {
          message("message", "D", null, "X", null, null, "hello"),
          message("message", "X", null, "U", null, null, "hello"),
          message("message", "D", null, "B", null, null, "hello"),
          message("message", "D", null, "U", "bot", null, "hello"),
          message("message", "D", null, "U", null, "message_changed", "hello"),
          message("message", "D", null, "U", null, "message_deleted", "hello")
        }) assertFalse(SlackAdmission.accept(p, "B", m, true));
    assertFalse(
        SlackAdmission.accept(
            new SlackProperties(),
            "B",
            message("message", "D", null, "U", null, null, "hello"),
            false));
  }

  /** Slackとの連携で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void messageIdentityIsSharedAcrossEventTypes() {
    assertEquals(
        message("message", "C", null, "U", null, null, "hi").key(),
        message("app_mention", "C", null, "U", null, null, "hi").key());
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void explicitMentionCanInviteBotToAnExistingThreadButUnmentionedMessagesCannot() {
    assertTrue(
        SlackAdmission.accept(
            settings(),
            "B",
            message("app_mention", "C", "parent", "U", null, null, "<@B> help"),
            false));
    assertFalse(
        SlackAdmission.accept(
            settings(), "B", message("message", "C", "parent", "U", null, null, "help"), false));
  }

  /** Slackとの連携で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void rootThreadTimestampAndUnsupportedGroupDmAreDistinguished() {
    assertTrue(
        SlackAdmission.accept(
            settings(), "B", message("message", "D", "1", "U", null, null, "help"), false));
    assertFalse(
        SlackAdmission.accept(
            settings(),
            "B",
            new SlackMessage(
                "E", "T", "D", "1", null, "U", null, null, "message", "mpim", "<@B> help"),
            false));
  }
}
