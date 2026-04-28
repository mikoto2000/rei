package dev.mikoto2000.rei.smalltalk;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class SmallTalkJobTest {

  @Test
  void runDoesNothingWhenDisabled() {
    SmallTalkProperties properties = new SmallTalkProperties();
    properties.setEnabled(false);
    SmallTalkTopicGenerator generator = org.mockito.Mockito.mock(SmallTalkTopicGenerator.class);
    SmallTalkNotifier notifier = org.mockito.Mockito.mock(SmallTalkNotifier.class);
    SmallTalkJob job = new SmallTalkJob(properties, generator, notifier);

    job.run();

    verify(generator, never()).generate();
    verify(notifier, never()).notifyTopic(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void runGeneratesTopicAndNotifiesWhenEnabled() {
    SmallTalkProperties properties = new SmallTalkProperties();
    properties.setEnabled(true);
    SmallTalkTopicGenerator generator = org.mockito.Mockito.mock(SmallTalkTopicGenerator.class);
    SmallTalkNotifier notifier = org.mockito.Mockito.mock(SmallTalkNotifier.class);
    SmallTalkJob job = new SmallTalkJob(properties, generator, notifier);

    org.mockito.Mockito.when(generator.generate()).thenReturn("最近読んだ記事で印象に残ったものはありますか。");

    job.run();

    verify(generator).generate();
    verify(notifier).notifyTopic("最近読んだ記事で印象に残ったものはありますか。");
  }
}
