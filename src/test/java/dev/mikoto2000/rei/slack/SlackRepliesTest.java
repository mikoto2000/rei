package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SlackRepliesの結果と状態の契約を検証するテスト。 */
class SlackRepliesTest {
  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void convertsMarkdownWithoutTurningTextIntoSlackMentions() {
    assertEquals(
        "*title*\n&lt;@U&gt; &amp;\n~old~",
        SlackReplies.parts("**title**\n<@U> &\n~~old~~").getFirst());
  }

  /** Slackとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void splitsLongAnswersWithoutBreakingSurrogates() {
    var parts = SlackReplies.parts("あ\n" + "😀".repeat(6000));
    assertTrue(parts.size() > 1);
    assertTrue(parts.stream().allMatch(s -> s.length() <= 3000));
    assertEquals("あ\n" + "😀".repeat(6000), String.join("", parts));
  }

  /** Slackとの連携で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void redactsSlackCredentialsEvenWhenTheyAppearUnlabelled() {
    assertEquals("[REDACTED] [REDACTED]", SlackReplies.safe("xoxb-111-secret xapp-1-111-secret"));
  }
}
