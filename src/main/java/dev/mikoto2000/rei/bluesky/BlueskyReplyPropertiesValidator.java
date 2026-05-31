package dev.mikoto2000.rei.bluesky;

import org.springframework.stereotype.Component;

@Component
public class BlueskyReplyPropertiesValidator {

  public void validate(BlueskyProperties.BlueskyReplyProperties reply) {
    if (reply == null) {
      throw new IllegalStateException("Bluesky reply configuration is missing");
    }
    if (reply.getCheckIntervalSeconds() < 10) {
      throw new IllegalStateException("rei.bluesky.reply.check-interval-seconds must be >= 10");
    }
    if (reply.getFetchLimit() < 1 || reply.getFetchLimit() > 100) {
      throw new IllegalStateException("rei.bluesky.reply.fetch-limit must be between 1 and 100");
    }
    if (reply.getMaxPostAgeMinutes() < 1) {
      throw new IllegalStateException("rei.bluesky.reply.max-post-age-minutes must be >= 1");
    }
    for (BlueskyProperties.ReplyUser user : reply.getUsers()) {
      if (user.getHandle() == null || user.getHandle().isBlank()) {
        throw new IllegalStateException("rei.bluesky.reply.users[].handle must not be blank");
      }
      if (user.getProbability() < 0.0d || user.getProbability() > 1.0d) {
        throw new IllegalStateException("rei.bluesky.reply.users[].probability must be between 0.0 and 1.0");
      }
      if (user.getMaxRepliesPerDay() < 0) {
        throw new IllegalStateException("rei.bluesky.reply.users[].max-replies-per-day must be >= 0");
      }
    }
  }
}
