package dev.mikoto2000.rei.slack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlackRepliesTest {
  @Test void convertsMarkdownWithoutTurningTextIntoSlackMentions() {
    assertEquals("*title*\n&lt;@U&gt; &amp;\n~old~",SlackReplies.parts("**title**\n<@U> &\n~~old~~").getFirst());
  }
  @Test void splitsLongAnswersWithoutBreakingSurrogates() {
    var parts=SlackReplies.parts("あ\n"+"😀".repeat(6000));
    assertTrue(parts.size()>1);assertTrue(parts.stream().allMatch(s->s.length()<=3000));
    assertEquals("あ\n"+"😀".repeat(6000),String.join("",parts));
  }
  @Test void redactsSlackCredentialsEvenWhenTheyAppearUnlabelled() {
    assertEquals("[REDACTED] [REDACTED]",SlackReplies.safe("xoxb-111-secret xapp-1-111-secret"));
  }
}
