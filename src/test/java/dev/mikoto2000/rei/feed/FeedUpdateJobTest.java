package dev.mikoto2000.rei.feed;

import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeedUpdateJobTest {

  @Test
  void runUpdatesAllFeeds() {
    FeedUpdateService service = org.mockito.Mockito.mock(FeedUpdateService.class);
    org.mockito.Mockito.when(service.updateAll()).thenReturn(List.of(
        new FeedUpdateResult(1L, "Example Feed", 2, null)));
    FeedUpdateJob job = new FeedUpdateJob(service);

    job.run();

    verify(service).updateAll();
  }

  @Test
  void runUsesDailyFourAmCronByDefault() throws Exception {
    Scheduled scheduled = FeedUpdateJob.class.getMethod("run").getAnnotation(Scheduled.class);

    assertEquals("${rei.feed.cron:0 0 4 * * *}", scheduled.cron());
    assertTrue(scheduled.fixedDelayString().isBlank());
  }
}
