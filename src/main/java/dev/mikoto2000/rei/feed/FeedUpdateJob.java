package dev.mikoto2000.rei.feed;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FeedUpdateJob {

  private final FeedUpdateService feedUpdateService;

  @Scheduled(cron = "${rei.feed.cron:0 0 4 * * *}")
  public void run() {
    for (FeedUpdateResult result : feedUpdateService.updateAll()) {
      if (result.errorMessage() == null || result.errorMessage().isBlank()) {
        System.out.println("feed update | " + result.feedName() + " | +" + result.addedItems());
      } else {
        System.out.println("feed update | " + result.feedName() + " | error | " + result.errorMessage());
      }
    }
  }
}
