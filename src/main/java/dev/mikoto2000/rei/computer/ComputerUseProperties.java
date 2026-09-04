package dev.mikoto2000.rei.computer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.computer-use")
public class ComputerUseProperties {
  private boolean enabled = false;
  private int maxDepth = 3;
  private int maxElements = 80;
  private int maxActions = 12;
  private boolean activeWindowOnly = true;
  private boolean visibleOnly = true;
  private Duration stabilizationDelay = Duration.ofMillis(150);

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public void setMaxDepth(int maxDepth) {
    this.maxDepth = maxDepth;
  }

  public int getMaxElements() {
    return maxElements;
  }

  public void setMaxElements(int maxElements) {
    this.maxElements = maxElements;
  }

  public int getMaxActions() {
    return maxActions;
  }

  public void setMaxActions(int maxActions) {
    this.maxActions = maxActions;
  }

  public boolean isActiveWindowOnly() {
    return activeWindowOnly;
  }

  public void setActiveWindowOnly(boolean activeWindowOnly) {
    this.activeWindowOnly = activeWindowOnly;
  }

  public boolean isVisibleOnly() {
    return visibleOnly;
  }

  public void setVisibleOnly(boolean visibleOnly) {
    this.visibleOnly = visibleOnly;
  }

  public Duration getStabilizationDelay() {
    return stabilizationDelay;
  }

  public void setStabilizationDelay(Duration stabilizationDelay) {
    this.stabilizationDelay = stabilizationDelay;
  }
}
