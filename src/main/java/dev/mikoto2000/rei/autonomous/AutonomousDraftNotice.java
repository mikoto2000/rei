package dev.mikoto2000.rei.autonomous;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** A local review reference, deliberately excluding generated text and private reasons. */
public record AutonomousDraftNotice(String draftId, boolean reviewRequired, Instant createdAt) {
  public AutonomousDraftNotice {
    if (draftId == null || !draftId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,255}") || createdAt == null)
      throw new IllegalArgumentException("Invalid autonomous notification reference");
  }

  public String notificationId() {
    return UUID.nameUUIDFromBytes(("autonomous-draft:" + draftId).getBytes(StandardCharsets.UTF_8))
        .toString();
  }
}
