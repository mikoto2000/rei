package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** フィード更新実行する作業の結果と状態の契約を検証するテスト。 */
class FeedUpdateJobTest {

  /** フィードの購読と収集で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void runUpdatesAllFeeds() {
    FeedUpdateService service = org.mockito.Mockito.mock(FeedUpdateService.class);
    org.mockito.Mockito.when(service.updateAll())
        .thenReturn(List.of(new FeedUpdateResult(1L, "Example Feed", 2, null)));
    FeedUpdateJob job = new FeedUpdateJob(service);

    job.run();

    verify(service).updateAll();
  }

  /** フィードの購読と収集で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void runUsesDailyFourAmCronByDefault() throws Exception {
    Scheduled scheduled = FeedUpdateJob.class.getMethod("run").getAnnotation(Scheduled.class);

    assertEquals("${rei.feed.cron:0 0 4 * * *}", scheduled.cron());
    assertTrue(scheduled.fixedDelayString().isBlank());
  }
}
