package dev.mikoto2000.rei.feed;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class FeedUpdateJob {

  private final FeedUpdateService feedUpdateService;

  @Scheduled(fixedDelayString = "${rei.feed.poll-interval-ms:3600000}")
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
