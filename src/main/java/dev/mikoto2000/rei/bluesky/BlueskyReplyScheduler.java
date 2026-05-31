package dev.mikoto2000.rei.bluesky;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyReplyScheduler {

  private final BlueskyReplyService blueskyReplyService;

  @Scheduled(fixedDelayString = "#{${rei.bluesky.reply.check-interval-seconds:300} * 1000}")
  public void run() {
    blueskyReplyService.runOnce();
  }
}
