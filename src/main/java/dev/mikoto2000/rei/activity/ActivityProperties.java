package dev.mikoto2000.rei.activity;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("rei.activity")
public class ActivityProperties {
  private boolean enabled = false;
  private boolean extractionEnabled = true;
  private boolean keepScreenshots = false;
  private boolean keepOnExtractionFailure = false;
  private int captureIntervalSeconds = 60;
  private int screenshotRetentionDays = 3;
  private double changeThreshold = .03;
  private int sessionGapSeconds = 90;
  private String zone = java.time.ZoneId.systemDefault().getId();
  private List<String> excludedProcesses = List.of("KeePassXC.exe", "1Password.exe");
  private List<String> excludedWindowTitlePatterns = List.of("*Password*", "*Private Browsing*", "*InPrivate*");

  public void validate() {
    if (captureIntervalSeconds < 1 || screenshotRetentionDays < 0 || sessionGapSeconds < 0
        || !Double.isFinite(changeThreshold) || changeThreshold < 0 || changeThreshold > 1
        || excludedProcesses == null || excludedWindowTitlePatterns == null)
      throw new IllegalArgumentException("Invalid rei.activity settings");
    java.time.ZoneId.of(zone);
  }
}
