package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Bluesky返信設定の結果と状態の契約を検証するテスト。 */
class BlueskyReplyPropertiesTest {

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void defaults() {
    BlueskyProperties.BlueskyReplyProperties reply = new BlueskyProperties.BlueskyReplyProperties();

    assertFalse(reply.isEnabled());
    assertFalse(reply.isDryRun());
    assertEquals(300, reply.getCheckIntervalSeconds());
    assertEquals(30, reply.getFetchLimit());
    assertEquals(true, reply.isExcludeReplies());
    assertEquals(true, reply.isExcludeReposts());
    assertEquals(120, reply.getMaxPostAgeMinutes());
    assertEquals(0, reply.getUsers().size());
  }
}
