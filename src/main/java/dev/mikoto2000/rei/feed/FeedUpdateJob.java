package dev.mikoto2000.rei.feed;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** フィードの購読と収集に必要なフィード更新実行する作業を扱う。 */
@Component
@RequiredArgsConstructor
public class FeedUpdateJob {

  /** フィード更新サービス。 */
  private final FeedUpdateService feedUpdateService;

  /** フィードの購読と収集の操作を実行する。 */
  @Scheduled(cron = "${rei.feed.cron:0 0 4 * * *}")
  public void run() {
    for (FeedUpdateResult result : feedUpdateService.updateAll()) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        System.out.println("feed update | " + result.feedName() + " | +" + result.addedItems());
      } else {
        System.out.println(
            "feed update | " + result.feedName() + " | error | " + result.errorMessage());
      }
    }
  }
}
