package dev.mikoto2000.rei.ui.shell.sound;

import dev.mikoto2000.rei.topic.AgentMessage;
import dev.mikoto2000.rei.topic.MessageOrigin;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * ターミナルの音通知に必要なエージェントメッセージ読み上げの案内を扱う。
 *
 * <p>Narrates delivered behavior notices without holding the behavior service's delivery lock.
 */
@Component
public class AgentMessageNarrator {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AgentMessageNarrator.class);

  /** 読み上げの案内。 */
  private final ChatResponseNarrator narrator;

  /** 設定。 */
  private final SoundNotificationProperties settings;

  /** 実行先。 */
  private final Executor executor;

  /** 指定された値や利用先を使って、AgentMessageNarratorを初期化する。 */
  public AgentMessageNarrator(
      ChatResponseNarrator narrator,
      SoundNotificationProperties settings,
      @Qualifier("agentRunExecutor") Executor executor) {
    this.narrator = narrator;
    this.settings = settings;
    this.executor = executor;
  }

  /** 通知に応じて状態や表示を更新するため、公開を受け付ける。 */
  public void onPublished(AgentMessage message) {
    if (!settings.isEnabled()
        || message == null
        || message.origin() != MessageOrigin.BEHAVIOR
        || !"assistant".equalsIgnoreCase(message.role())
        || message.content() == null
        || message.content().isBlank()) return;
    try {
      executor.execute(
          () -> {
            try {
              if (settings.isEnabled()) narrator.narrateCompletedRun(message.content());
            } catch (RuntimeException error) {
              log.warn("Behavior narration failed ({})", error.getClass().getSimpleName());
            }
          });
    } catch (RejectedExecutionException closing) {
      log.debug("Behavior narration skipped during shutdown");
    }
  }
}
