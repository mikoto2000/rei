package dev.mikoto2000.rei.image;

import java.util.UUID;

import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

/** Publishes lifecycle events around an image-model invocation. */
final class AgentEventImageModel implements ImageModel {

  private final String feature;
  private final ImageModel delegate;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;

  AgentEventImageModel(String feature, ImageModel delegate, AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.feature = feature;
    this.delegate = delegate;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  @Override
  public ImageResponse call(ImagePrompt prompt) {
    String requestId = UUID.randomUUID().toString();
    long startedAtNanos = System.nanoTime();
    eventPublisher.publish(eventFactory.llmRequestStarted(null, requestId, feature));
    ImageResponse response = delegate.call(prompt);
    eventPublisher.publish(eventFactory.llmResponseCompleted(null, requestId,
        (System.nanoTime() - startedAtNanos) / 1_000_000L));
    return response;
  }
}
