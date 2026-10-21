package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Bluesky返信設定検証の窓口の結果と状態の契約を検証するテスト。 */
class BlueskyReplyPropertiesValidatorTest {

  /** 検証に使う検証の窓口。 */
  private final BlueskyReplyPropertiesValidator validator = new BlueskyReplyPropertiesValidator();

  /** 検証について、有効な設定を受け付けられることを確認する。 */
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

  /** 検証について、空白の操作対象を受け付けないことを確認する。 */
  @Test
  void validateRejectsBlankHandle() {
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();
    BlueskyProperties.ReplyUser user = new BlueskyProperties.ReplyUser();
    user.setHandle(" ");
    user.setProbability(0.25d);
    reply.getUsers().add(user);

    assertThrows(IllegalStateException.class, () -> validator.validate(reply));
  }

  /** 検証について、出力先所属対象範囲確率を受け付けないことを確認する。 */
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
