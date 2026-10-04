package dev.mikoto2000.rei.memory.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.memory.auto-sleep")
public record AutoSleepProperties(boolean enabled, Duration minimumIdle, int minimumTurns, Duration retryInterval) {
  public AutoSleepProperties {
    if (minimumIdle == null) minimumIdle=Duration.ofMinutes(5);
    if (retryInterval == null) retryInterval=Duration.ofMinutes(10);
    if (minimumTurns == 0) minimumTurns=5;
    if (minimumIdle.isNegative() || minimumIdle.isZero() || retryInterval.isNegative()
        || retryInterval.isZero() || minimumTurns < 1) throw new IllegalArgumentException("Invalid Auto Sleep limits");
  }
}
