package dev.mikoto2000.rei.dot;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class DotMaintenance {
  private final DotRequestRepository repo;

  DotMaintenance(DotRequestRepository repo) {
    this.repo = repo;
  }

  @Scheduled(fixedDelayString = "${rei.dot-delegation.maintenance-interval-ms:5000}")
  void maintain() {
    try {
      repo.maintain();
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn("Dot maintenance unavailable: {}", failure.getClass().getSimpleName());
    }
  }
}
