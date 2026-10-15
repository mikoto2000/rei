package dev.mikoto2000.rei.memory.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.memory.auto-sleep")
public record AutoSleepProperties(
    boolean enabled,
    Duration minimumIdle,
    int minimumTurns,
    Duration retryInterval,
    String cron,
    String zone,
    boolean onSessionEnd,
    boolean onShutdown,
    int failureLimit,
    int maxTimeoutMultiplier,
    Duration maxTimeout,
    Duration totalTimeout) {
  public AutoSleepProperties(
      boolean enabled,
      Duration minimumIdle,
      int minimumTurns,
      Duration retryInterval,
      String cron,
      String zone,
      boolean onSessionEnd,
      boolean onShutdown) {
    this(
        enabled,
        minimumIdle,
        minimumTurns,
        retryInterval,
        cron,
        zone,
        onSessionEnd,
        onShutdown,
        3,
        4,
        Duration.ofMinutes(8),
        Duration.ofMinutes(10));
  }

  public AutoSleepProperties(
      boolean enabled,
      Duration minimumIdle,
      int minimumTurns,
      Duration retryInterval,
      String cron,
      String zone) {
    this(enabled, minimumIdle, minimumTurns, retryInterval, cron, zone, false, false);
  }

  public AutoSleepProperties(
      boolean enabled, Duration minimumIdle, int minimumTurns, Duration retryInterval) {
    this(enabled, minimumIdle, minimumTurns, retryInterval, null, null);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public AutoSleepProperties {
    if (failureLimit == 0) failureLimit = 3;
    if (maxTimeoutMultiplier == 0) maxTimeoutMultiplier = 4;
    if (maxTimeout == null) maxTimeout = Duration.ofMinutes(8);
    if (totalTimeout == null) totalTimeout = Duration.ofMinutes(10);
    if (failureLimit < 1
        || failureLimit > 100
        || maxTimeoutMultiplier < 1
        || maxTimeoutMultiplier > 1024
        || maxTimeout.isNegative()
        || maxTimeout.isZero()
        || totalTimeout.isNegative()
        || totalTimeout.isZero()
        || maxTimeout.compareTo(Duration.ofDays(1)) > 0
        || totalTimeout.compareTo(Duration.ofDays(1)) > 0)
      throw new IllegalArgumentException("Invalid Auto Sleep failure/time limits");
    if (cron != null && cron.isBlank()) cron = null;
    if (zone == null || zone.isBlank()) zone = java.time.ZoneId.systemDefault().getId();
    try {
      java.time.ZoneId.of(zone);
    } catch (java.time.DateTimeException invalid) {
      throw new IllegalArgumentException("Invalid Auto Sleep time zone", invalid);
    }
    if (cron != null) {
      if (cron.length() > 256) throw new IllegalArgumentException("Auto Sleep cron is too long");
      org.springframework.scheduling.support.CronExpression.parse(cron);
    }
    if (minimumIdle == null) minimumIdle = Duration.ofMinutes(5);
    if (retryInterval == null) retryInterval = Duration.ofMinutes(10);
    if (minimumTurns == 0) minimumTurns = 5;
    if (minimumIdle.isNegative()
        || minimumIdle.isZero()
        || retryInterval.isNegative()
        || retryInterval.isZero()
        || minimumTurns < 1) throw new IllegalArgumentException("Invalid Auto Sleep limits");
  }
}
