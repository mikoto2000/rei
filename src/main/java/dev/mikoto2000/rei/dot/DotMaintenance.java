package dev.mikoto2000.rei.dot;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 利用者の状態表示に必要な状態表示保守を扱う。 */
@Component
final class DotMaintenance {
  /** リポジトリ。 */
  private final DotRequestRepository repo;

  /** 指定された値や利用先を使って、DotMaintenanceを初期化する。 */
  DotMaintenance(DotRequestRepository repo) {
    this.repo = repo;
  }

  /** 利用者の状態表示に関するmaintainを処理する。 */
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
