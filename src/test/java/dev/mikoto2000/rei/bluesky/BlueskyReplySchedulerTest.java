package dev.mikoto2000.rei.bluesky;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlueskyReplySchedulerTest {

  @Mock
  private BlueskyReplyService service;

  @Test
  void delegatesToService() {
    BlueskyReplyScheduler scheduler = new BlueskyReplyScheduler(service);

    scheduler.run();

    verify(service).runOnce();
  }
}
