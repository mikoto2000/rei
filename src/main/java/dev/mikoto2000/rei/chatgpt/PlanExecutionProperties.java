package dev.mikoto2000.rei.chatgpt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatGPTとの連携で受け渡す計画実行設定を表す。
 *
 * @param enabled 有効状態
 * @param maxIterations 最大繰り返し回数
 * @param maxRepairs 最大修復一覧
 * @param maxConcurrent 最大並行
 * @param maxInputBytes 最大入力バイト数
 * @param maxOutputBytes 最大出力バイト数
 * @param timeout タイムアウト
 * @param semanticValidation 意味による検証
 * @param refinementRounds refinement実行段階数
 */
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
  /** 既定タイムアウト。 */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

  /** 最大タイムアウト。 */
  public static final Duration MAX_TIMEOUT = Duration.ofMinutes(30);

  /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
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

  /** 受け取った情報からPlanExecutionPropertiesを作成する。 */
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

  /** 利用先の上限を超えない量へ、指定された内容を調整する。 */
  private static int bounded(Integer value, int fallback, int min, int max) {
    int result = value == null ? fallback : value;
    if (result < min || result > max)
      throw new IllegalArgumentException("Invalid Plan execution limit");
    return result;
  }
}
