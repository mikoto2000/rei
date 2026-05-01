package dev.mikoto2000.rei.interest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InterestNotificationJob {

  private static final Logger log = LoggerFactory.getLogger(InterestNotificationJob.class);

  private final InterestDiscoveryJob interestDiscoveryJob;
  private final InterestNotifier interestNotifier;
  private final InterestProperties properties;

  @Scheduled(cron = "${rei.interest.notification-cron:0 0 12 * * *}")
  public void run() {
    if (!properties.isNotificationEnabled()) {
      log.info("Interest notification job skipped because notificationEnabled=false");
      return;
    }

    log.info("Interest notification job started");
    java.util.List<InterestUpdate> updates = interestDiscoveryJob.discoverNow(message -> log.info("Interest discovery: {}", message));
    log.info("Interest notification job discovered {} new updates", updates.size());
    for (InterestUpdate update : updates) {
      log.info("Interest notification job emitting update: topic={}, summary={}", update.topic(), update.summary());
      interestNotifier.notifyUpdate(update);
    }
    log.info("Interest notification job finished");
  }
}
