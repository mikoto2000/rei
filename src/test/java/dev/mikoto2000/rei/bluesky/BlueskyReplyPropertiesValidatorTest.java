package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlueskyReplyPropertiesValidatorTest {

  private final BlueskyReplyPropertiesValidator validator = new BlueskyReplyPropertiesValidator();

  @Test
  void validateAcceptsValidConfig() {
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();
    BlueskyProperties.ReplyUser user = new BlueskyProperties.ReplyUser();
    user.setHandle("alice.bsky.social");
    user.setProbability(0.25d);
    user.setMaxRepliesPerDay(3);
    reply.getUsers().add(user);

    assertDoesNotThrow(() -> validator.validate(reply));
  }

  @Test
  void validateRejectsBlankHandle() {
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();
    BlueskyProperties.ReplyUser user = new BlueskyProperties.ReplyUser();
    user.setHandle(" ");
    user.setProbability(0.25d);
    reply.getUsers().add(user);

    assertThrows(IllegalStateException.class, () -> validator.validate(reply));
  }

  @Test
  void validateRejectsOutOfRangeProbability() {
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();
    BlueskyProperties.ReplyUser user = new BlueskyProperties.ReplyUser();
    user.setHandle("alice.bsky.social");
    user.setProbability(1.2d);
    reply.getUsers().add(user);

    assertThrows(IllegalStateException.class, () -> validator.validate(reply));
  }
}
