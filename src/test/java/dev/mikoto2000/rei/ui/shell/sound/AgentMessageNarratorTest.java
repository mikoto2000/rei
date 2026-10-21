package dev.mikoto2000.rei.ui.shell.sound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/** エージェントメッセージ読み上げの案内の結果と状態の契約を検証するテスト。 */
class AgentMessageNarratorTest {
  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void behaviorIsNarratedAsynchronouslyWithoutChangingCommandFlag() {
    var sound = mock(SoundNotificationService.class);
    var narrator = new ChatResponseNarrator(sound);
    var settings = new SoundNotificationProperties();
    settings.setEnabled(true);
    var tasks = new ArrayList<Runnable>();
    var messages = new AgentMessageNarrator(narrator, settings, tasks::add);
    messages.onPublished(message(MessageOrigin.BEHAVIOR, "assistant", "**休憩**しましょう"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(sound);
    assertEquals(1, tasks.size());
    tasks.getFirst().run();
    verify(sound).notify("休憩しましょう");
    assertFalse(narrator.wasNarrated());
  }

  /** ターミナルの音通知で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disabledOtherOriginsAndEmptyMessagesAreNotQueued() {
    var settings = new SoundNotificationProperties();
    var narrator = mock(ChatResponseNarrator.class);
    var tasks = new ArrayList<Runnable>();
    var messages = new AgentMessageNarrator(narrator, settings, tasks::add);
    messages.onPublished(message(MessageOrigin.BEHAVIOR, "assistant", "notice"));
    settings.setEnabled(true);
    messages.onPublished(message(MessageOrigin.TOPIC_GENERATOR, "assistant", "topic"));
    messages.onPublished(message(MessageOrigin.NORMAL_RESPONSE, "assistant", "answer"));
    messages.onPublished(message(MessageOrigin.BEHAVIOR, "user", "user"));
    messages.onPublished(message(MessageOrigin.BEHAVIOR, "assistant", " "));
    messages.onPublished(null);
    assertTrue(tasks.isEmpty());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(narrator);
  }

  /** ターミナルの音通知で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void audioFailureDoesNotFailMessageDelivery() {
    var settings = new SoundNotificationProperties();
    settings.setEnabled(true);
    var narrator = mock(ChatResponseNarrator.class);
    doThrow(new IllegalStateException()).when(narrator).narrateCompletedRun(anyString());
    assertDoesNotThrow(
        () ->
            new AgentMessageNarrator(narrator, settings, Runnable::run)
                .onPublished(message(MessageOrigin.BEHAVIOR, "assistant", "notice")));
    assertDoesNotThrow(
        () ->
            new AgentMessageNarrator(
                    narrator,
                    settings,
                    task -> {
                      throw new java.util.concurrent.RejectedExecutionException();
                    })
                .onPublished(message(MessageOrigin.BEHAVIOR, "assistant", "notice")));
  }

  /** {@code message} の条件を再現するための補助情報を返す。 */
  private AgentMessage message(MessageOrigin origin, String role, String content) {
    return new AgentMessage("id", role, content, origin, Instant.now());
  }
}
