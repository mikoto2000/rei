package dev.mikoto2000.rei.topic;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 話題生成の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@ConfigurationProperties(prefix = "rei.topic-generator")
public class TopicGeneratorProperties {
  /** 有効状態。 */
  private boolean enabled = false;

  /** 最大候補一覧。 */
  private int maxCandidates = 5;

  /** 最小評価値。 */
  private double minimumScore = 0.65d;

  /** 最小確信度。 */
  private double minimumConfidence = 0.70d;

  /** 最小話題読み上げ実行間隔。 */
  private Duration minimumTopicSpeakInterval = Duration.ofMinutes(30);

  /** 候補最大経過時間。 */
  private Duration candidateMaxAge = Duration.ofMinutes(30);

  /** 操作がない状態実行条件。 */
  private final IdleTrigger idleTrigger = new IdleTrigger();

  /** 情報への関心。 */
  private final Curiosity curiosity = new Curiosity();

  /** 利用先の検出。 */
  private final Discovery discovery = new Discovery();

  /** 話題の管理において、有効状態かどうかを判定する。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** 話題の管理で使用する有効状態を設定する。 */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** 話題の管理で参照する最大候補一覧を返す。 */
  public int getMaxCandidates() {
    return maxCandidates;
  }

  /** 話題の管理で使用する最大候補一覧を設定する。 */
  public void setMaxCandidates(int maxCandidates) {
    this.maxCandidates = maxCandidates;
  }

  /** 話題の管理で参照する最小評価値を返す。 */
  public double getMinimumScore() {
    return minimumScore;
  }

  /** 話題の管理で使用する最小評価値を設定する。 */
  public void setMinimumScore(double minimumScore) {
    this.minimumScore = minimumScore;
  }

  /** 話題の管理で参照する最小確信度を返す。 */
  public double getMinimumConfidence() {
    return minimumConfidence;
  }

  /** 話題の管理で使用する最小確信度を設定する。 */
  public void setMinimumConfidence(double minimumConfidence) {
    this.minimumConfidence = minimumConfidence;
  }

  /** 話題の管理で参照する最小話題読み上げ実行間隔を返す。 */
  public Duration getMinimumTopicSpeakInterval() {
    return minimumTopicSpeakInterval;
  }

  /** 話題の管理で使用する最小話題読み上げ実行間隔を設定する。 */
  public void setMinimumTopicSpeakInterval(Duration minimumTopicSpeakInterval) {
    this.minimumTopicSpeakInterval = minimumTopicSpeakInterval;
  }

  /** 話題の管理で参照する候補最大経過時間を返す。 */
  public Duration getCandidateMaxAge() {
    return candidateMaxAge;
  }

  /** 話題の管理で使用する候補最大経過時間を設定する。 */
  public void setCandidateMaxAge(Duration candidateMaxAge) {
    this.candidateMaxAge = candidateMaxAge;
  }

  /** 話題の管理で参照する操作がない状態実行条件を返す。 */
  public IdleTrigger getIdleTrigger() {
    return idleTrigger;
  }

  /** 話題の管理で参照する情報への関心を返す。 */
  public Curiosity getCuriosity() {
    return curiosity;
  }

  /** 話題の管理で参照する利用先の検出を返す。 */
  public Discovery getDiscovery() {
    return discovery;
  }

  /** 話題の管理に必要な操作がない状態実行条件を扱う。 */
  public static class IdleTrigger {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 確認実行間隔。 */
    private Duration checkInterval = Duration.ofSeconds(30);

    /** 最小操作がない状態。 */
    private Duration minimumIdle = Duration.ofMinutes(2);

    /** 話題の管理において、有効状態かどうかを判定する。 */
    public boolean isEnabled() {
      return enabled;
    }

    /** 話題の管理で使用する有効状態を設定する。 */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** 話題の管理で参照する確認実行間隔を返す。 */
    public Duration getCheckInterval() {
      return checkInterval;
    }

    /** 話題の管理で使用する確認実行間隔を設定する。 */
    public void setCheckInterval(Duration checkInterval) {
      this.checkInterval = checkInterval;
    }

    /** 話題の管理で参照する最小操作がない状態を返す。 */
    public Duration getMinimumIdle() {
      return minimumIdle;
    }

    /** 話題の管理で使用する最小操作がない状態を設定する。 */
    public void setMinimumIdle(Duration minimumIdle) {
      this.minimumIdle = minimumIdle;
    }
  }

  /** 話題の管理に必要な情報への関心を扱う。 */
  public static class Curiosity {
    /** 有効期限。 */
    private Duration expiration = Duration.ofDays(30);

    /** 話題の管理で参照する有効期限を返す。 */
    public Duration getExpiration() {
      return expiration;
    }

    /** 話題の管理で使用する有効期限を設定する。 */
    public void setExpiration(Duration expiration) {
      this.expiration = expiration;
    }
  }

  /** 話題の管理に必要な利用先の検出を扱う。 */
  public static class Discovery {
    /** 有効状態。 */
    private boolean enabled = true;

    /** 最大開始時の候補。 */
    private int maxSeeds = 3;

    /** 最小関連度。 */
    private double minimumRelevance = 0.70d;

    /** 情報の鮮度範囲日数。 */
    private int freshnessWindowDays = 30;

    /** 話題の管理において、有効状態かどうかを判定する。 */
    public boolean isEnabled() {
      return enabled;
    }

    /** 話題の管理で使用する有効状態を設定する。 */
    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    /** 話題の管理で参照する最大開始時の候補を返す。 */
    public int getMaxSeeds() {
      return maxSeeds;
    }

    /** 話題の管理で使用する最大開始時の候補を設定する。 */
    public void setMaxSeeds(int maxSeeds) {
      this.maxSeeds = maxSeeds;
    }

    /** 話題の管理で参照する最小関連度を返す。 */
    public double getMinimumRelevance() {
      return minimumRelevance;
    }

    /** 話題の管理で使用する最小関連度を設定する。 */
    public void setMinimumRelevance(double minimumRelevance) {
      this.minimumRelevance = minimumRelevance;
    }

    /** 話題の管理で参照する情報の鮮度範囲日数を返す。 */
    public int getFreshnessWindowDays() {
      return freshnessWindowDays;
    }

    /** 話題の管理で使用する情報の鮮度範囲日数を設定する。 */
    public void setFreshnessWindowDays(int freshnessWindowDays) {
      this.freshnessWindowDays = freshnessWindowDays;
    }
  }
}
