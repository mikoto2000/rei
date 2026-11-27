package dev.mikoto2000.rei.autonomous;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;

/** Settings for consideration and drafts. They never grant permission to publish. */
@ConfigurationProperties(prefix = "rei.autonomous-action", ignoreUnknownFields = false)
public record AutonomousActionProperties(
    @DefaultValue("false") boolean enabled, @DefaultValue Bluesky bluesky) {
  public AutonomousActionProperties {
    if (bluesky == null) bluesky = Bluesky.defaults();
  }

  public record Bluesky(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("0 0 12 * * *") String cron,
      @DefaultValue("") String zone,
      @DefaultValue("1") int maxDraftsPerDay,
      @DefaultValue("1") int maxPendingDrafts,
      @DefaultValue("PT1H") Duration cooldown,
      @DefaultValue("PT72H") Duration draftExpiration,
      @DefaultValue("3") int maxRegenerations,
      @DefaultValue("PT1H") Duration regenerationCooldown,
      @DefaultValue("あなたは AI 秘書のれいです。公開許可された話題から投稿案を考えてください。話題がなければ SKIP を返してください。")
          String systemPrompt) {
    public Bluesky {
      CronExpression.parse(Objects.requireNonNull(cron, "cron"));
      zone = Objects.requireNonNull(zone, "zone");
      if (!zone.isBlank()) ZoneId.of(zone);
      if (maxDraftsPerDay < 1 || maxPendingDrafts < 1 || maxRegenerations < 0)
        throw new IllegalArgumentException("Invalid autonomous draft limits");
      requirePositive(cooldown);
      requirePositive(draftExpiration);
      requirePositive(regenerationCooldown);
      if (systemPrompt == null || systemPrompt.isBlank() || systemPrompt.length() > 16384)
        throw new IllegalArgumentException("Invalid autonomous system prompt");
    }

    private static void requirePositive(Duration duration) {
      if (duration == null
          || duration.isNegative()
          || duration.isZero()
          || duration.compareTo(Duration.ofDays(3660)) > 0)
        throw new IllegalArgumentException("Invalid autonomous draft duration");
    }

    public static Bluesky defaults() {
      return new Bluesky(
          false,
          "0 0 12 * * *",
          "",
          1,
          1,
          Duration.ofHours(1),
          Duration.ofHours(72),
          3,
          Duration.ofHours(1),
          "あなたは AI 秘書のれいです。公開許可された話題から投稿案を考えてください。話題がなければ SKIP を返してください。");
    }
  }
}
