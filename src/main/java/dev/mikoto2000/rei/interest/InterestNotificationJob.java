package dev.mikoto2000.rei.interest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterestNotificationJob {

  private final InterestDiscoveryJob interestDiscoveryJob;
  private final InterestNotifier interestNotifier;
  private final InterestProperties properties;

  @Scheduled(cron = "${rei.interest.notification-cron:0 0 12 * * *}")
  public void run() {
    if (!properties.isNotificationEnabled()) {
      return;
    }

    for (InterestUpdate update : interestDiscoveryJob.discoverNow()) {
      interestNotifier.notifyUpdate(update);
    }
  }
}
