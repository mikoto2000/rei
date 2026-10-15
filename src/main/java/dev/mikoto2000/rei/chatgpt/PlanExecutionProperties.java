package dev.mikoto2000.rei.chatgpt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.chatgpt-plan.execution")
public record PlanExecutionProperties(
    boolean enabled,
    Integer maxIterations,
    Integer maxRepairs,
    Integer maxConcurrent,
    Integer maxInputBytes,
    Integer maxOutputBytes,
    Duration timeout,
    boolean semanticValidation,
    Integer refinementRounds) {
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

  public PlanExecutionProperties(
      boolean enabled,
      Integer maxIterations,
      Integer maxRepairs,
      Integer maxConcurrent,
      Integer maxInputBytes,
      Integer maxOutputBytes,
      Duration timeout,
      boolean semanticValidation) {
    this(
        enabled,
        maxIterations,
        maxRepairs,
        maxConcurrent,
        maxInputBytes,
        maxOutputBytes,
        timeout,
        semanticValidation,
        0);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PlanExecutionProperties {
    maxIterations = bounded(maxIterations, 3, 1, 8);
    maxRepairs = bounded(maxRepairs, 2, 0, 3);
    maxConcurrent = bounded(maxConcurrent, 2, 1, 2);
    maxInputBytes = bounded(maxInputBytes, 262144, 1024, 262144);
    maxOutputBytes = bounded(maxOutputBytes, 1048576, 1024, 1048576);
    refinementRounds = bounded(refinementRounds, 0, 0, 2);
    if (refinementRounds >= maxIterations)
      throw new IllegalArgumentException("Refinement exceeds inference budget");
    timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0)
      throw new IllegalArgumentException(
          "rei.chatgpt-plan.execution.timeout must be positive and at most 30m");
  }

  private static int bounded(Integer value, int fallback, int min, int max) {
    int result = value == null ? fallback : value;
    if (result < min || result > max)
      throw new IllegalArgumentException("Invalid Plan execution limit");
    return result;
  }
}
