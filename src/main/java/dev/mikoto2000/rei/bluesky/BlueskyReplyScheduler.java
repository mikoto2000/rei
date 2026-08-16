package dev.mikoto2000.rei.bluesky;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyReplyScheduler {
  private static final Logger log = LoggerFactory.getLogger(BlueskyReplyScheduler.class);

  private final BlueskyReplyService blueskyReplyService;
  private final AtomicBoolean running = new AtomicBoolean(false);

  @Scheduled(fixedDelayString = "#{${rei.bluesky.reply.check-interval-seconds:300} * 1000}")
  public void run() {
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
