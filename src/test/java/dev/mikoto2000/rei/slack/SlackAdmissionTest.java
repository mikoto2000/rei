package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SlackAdmissionTest {
  private SlackProperties settings() {
    var p = new SlackProperties();
    p.setAllowedWorkspaceIds(Set.of("T"));
    p.setAllowedUserIds(Set.of("U"));
    p.setAllowedChannelIds(Set.of("C", "D"));
    return p;
  }

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

  @Test
  void messageIdentityIsSharedAcrossEventTypes() {
    assertEquals(
        message("message", "C", null, "U", null, null, "hi").key(),
        message("app_mention", "C", null, "U", null, null, "hi").key());
  }

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
