package dev.mikoto2000.rei.smalltalk;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class SmallTalkJob {

  private final SmallTalkProperties properties;
  private final SmallTalkTopicGenerator generator;
  private final SmallTalkNotifier notifier;

  @Scheduled(cron = "${rei.small-talk.cron:0 0 12 * * *}")
  public void run() {
    if (!properties.isEnabled()) {
      return;
    }

    String topic = generator.generate();
    if (topic == null || topic.isBlank()) {
      return;
    }
    notifier.notifyTopic(topic.trim());
  }
}
