package dev.mikoto2000.rei.agent.progress;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.agent.progress")
public class AgentProgressProperties {

  private boolean enabled = true;
  private int maxNoProgressIterations = 3;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getMaxNoProgressIterations() {
    return maxNoProgressIterations;
  }

  public void setMaxNoProgressIterations(int maxNoProgressIterations) {
    this.maxNoProgressIterations = Math.max(1, maxNoProgressIterations);
  }
}
