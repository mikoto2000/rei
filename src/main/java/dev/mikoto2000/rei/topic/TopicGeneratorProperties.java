package dev.mikoto2000.rei.topic;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.topic-generator")
public class TopicGeneratorProperties {
  private boolean enabled = false;
  private int maxCandidates = 5;
  private double minimumScore = 0.65d;
  private double minimumConfidence = 0.70d;
  private Duration minimumTopicSpeakInterval = Duration.ofMinutes(30);
  private Duration candidateMaxAge = Duration.ofMinutes(30);
  private final IdleTrigger idleTrigger = new IdleTrigger();
  private final Curiosity curiosity = new Curiosity();
  private final Discovery discovery = new Discovery();

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean enabled) { this.enabled = enabled; }
  public int getMaxCandidates() { return maxCandidates; }
  public void setMaxCandidates(int maxCandidates) { this.maxCandidates = maxCandidates; }
  public double getMinimumScore() { return minimumScore; }
  public void setMinimumScore(double minimumScore) { this.minimumScore = minimumScore; }
  public double getMinimumConfidence() { return minimumConfidence; }
  public void setMinimumConfidence(double minimumConfidence) { this.minimumConfidence = minimumConfidence; }
  public Duration getMinimumTopicSpeakInterval() { return minimumTopicSpeakInterval; }
  public void setMinimumTopicSpeakInterval(Duration minimumTopicSpeakInterval) {
    this.minimumTopicSpeakInterval = minimumTopicSpeakInterval;
  }
  public Duration getCandidateMaxAge() { return candidateMaxAge; }
  public void setCandidateMaxAge(Duration candidateMaxAge) { this.candidateMaxAge = candidateMaxAge; }
  public IdleTrigger getIdleTrigger() { return idleTrigger; }
  public Curiosity getCuriosity() { return curiosity; }
  public Discovery getDiscovery() { return discovery; }

  public static class IdleTrigger {
    private boolean enabled = true;
    private Duration checkInterval = Duration.ofSeconds(30);
    private Duration minimumIdle = Duration.ofMinutes(2);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getCheckInterval() { return checkInterval; }
    public void setCheckInterval(Duration checkInterval) { this.checkInterval = checkInterval; }
    public Duration getMinimumIdle() { return minimumIdle; }
    public void setMinimumIdle(Duration minimumIdle) { this.minimumIdle = minimumIdle; }
  }

  public static class Curiosity {
    private Duration expiration = Duration.ofDays(30);
    public Duration getExpiration() { return expiration; }
    public void setExpiration(Duration expiration) { this.expiration = expiration; }
  }

  public static class Discovery {
    private boolean enabled = true;
    private int maxSeeds = 3;
    private double minimumRelevance = 0.70d;
    private int freshnessWindowDays = 30;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxSeeds() { return maxSeeds; }
    public void setMaxSeeds(int maxSeeds) { this.maxSeeds = maxSeeds; }
    public double getMinimumRelevance() { return minimumRelevance; }
    public void setMinimumRelevance(double minimumRelevance) { this.minimumRelevance = minimumRelevance; }
    public int getFreshnessWindowDays() { return freshnessWindowDays; }
    public void setFreshnessWindowDays(int freshnessWindowDays) { this.freshnessWindowDays = freshnessWindowDays; }
  }
}
