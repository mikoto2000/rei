package dev.mikoto2000.rei.image;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.util.UUID;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

/**
 * 画像の生成に必要なエージェントイベント画像モデルを扱う。
 *
 * <p>Publishes lifecycle events around an image-model invocation.
 */
final class AgentEventImageModel implements ImageModel {

  /** 機能。 */
  private final String feature;

  /** 委譲先。 */
  private final ImageModel delegate;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 指定された値や利用先を使って、AgentEventImageModelを初期化する。 */
  AgentEventImageModel(
      String feature,
      ImageModel delegate,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.feature = feature;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 画像の生成の要求を実行し、呼び出し元へ結果を返す。 */
  @Override
  public ImageResponse call(ImagePrompt prompt) {
    String requestId = UUID.randomUUID().toString();
    long startedAtNanos = System.nanoTime();
    eventPublisher.publish(eventFactory.llmRequestStarted(null, requestId, feature));
    try {
      ImageResponse response = delegate.call(prompt);
      eventPublisher.publish(
          eventFactory.llmResponseCompleted(
              null, requestId, (System.nanoTime() - startedAtNanos) / 1_000_000L));
      return response;
    } catch (RuntimeException e) {
      eventPublisher.publish(
          eventFactory.llmRequestFailed(
              null, requestId, (System.nanoTime() - startedAtNanos) / 1_000_000L, e));
      throw e;
    }
  }
}
