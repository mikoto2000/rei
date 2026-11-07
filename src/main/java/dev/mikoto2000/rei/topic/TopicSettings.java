package dev.mikoto2000.rei.topic;

import java.time.Duration;

/** 話題生成の各判断に使う、不変な設定値。 */
record TopicSettings(
    boolean enabled,
    int maxCandidates,
    double minimumScore,
    double minimumConfidence,
    Duration minimumTopicSpeakInterval,
    Duration candidateMaxAge,
    boolean idleEnabled,
    Duration idleCheckInterval,
    Duration minimumIdle,
    boolean discoveryEnabled,
    int maxSeeds,
    double minimumRelevance,
    int freshnessWindowDays) {
  /** 現在の設定から、変更用の参照を含まない値を取得する。 */
  static TopicSettings capture(TopicGeneratorProperties source) {
    var idle = source.getIdleTrigger();
    var discovery = source.getDiscovery();
    return new TopicSettings(
        source.isEnabled(),
        source.getMaxCandidates(),
        source.getMinimumScore(),
        source.getMinimumConfidence(),
        source.getMinimumTopicSpeakInterval(),
        source.getCandidateMaxAge(),
        idle.isEnabled(),
        idle.getCheckInterval(),
        idle.getMinimumIdle(),
        discovery.isEnabled(),
        discovery.getMaxSeeds(),
        discovery.getMinimumRelevance(),
        discovery.getFreshnessWindowDays());
  }
}
