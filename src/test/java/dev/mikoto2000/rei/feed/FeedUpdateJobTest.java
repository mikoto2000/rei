package dev.mikoto2000.rei.feed;

import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;

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
}
