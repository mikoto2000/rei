package dev.mikoto2000.rei.bluesky;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Blueskyとの連携に必要なBluesky返信実行予定の管理を扱う。 */
@Component
@RequiredArgsConstructor
public class BlueskyReplyScheduler {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(BlueskyReplyScheduler.class);

  /** Bluesky返信サービス。 */
  private final BlueskyReplyService blueskyReplyService;

  /** 実行中。 */
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** Blueskyとの連携の操作を実行する。 */
  @Scheduled(fixedDelayString = "#{${rei.bluesky.reply.check-interval-seconds:300} * 1000}")
  public void run() {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!running.compareAndSet(false, true)) {
      log.warn("Bluesky reply scheduler skipped: previous execution is still running");
      return;
    }
    Instant startedAt = Instant.now();
    try {
      log.debug("Bluesky reply scheduler started");
      blueskyReplyService.runOnce();
    } catch (RuntimeException e) {
      log.warn("Bluesky reply scheduler failed: message={}", e.getMessage(), e);
    } finally {
      running.set(false);
      long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();
      log.debug("Bluesky reply scheduler finished: elapsedMillis={}", elapsedMillis);
    }
  }
}
