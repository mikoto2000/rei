package dev.mikoto2000.rei.checkpoint;

@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rei.resume.overview")
public class ResumeOverviewProperties {
  private boolean enabled = true;
  private int failedMaxAgeDays = 30;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean value) {
    enabled = value;
  }

  public int getFailedMaxAgeDays() {
    return failedMaxAgeDays;
  }

  public void setFailedMaxAgeDays(int value) {
    if (value < 0) throw new IllegalArgumentException("Negative failed task age");
    failedMaxAgeDays = value;
  }
}
