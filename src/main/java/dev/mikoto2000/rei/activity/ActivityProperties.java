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
  /** Compatibility cap for explicitly configured Phase 3 settings. */
  private Integer summaryGapSeconds;
  private int summaryNormalMergeGapSeconds = 120;
  private int summaryMaximumMergeGapSeconds = 300;
  private double primaryConfidenceThreshold = .5;
  private int summaryBriefSwitchSeconds = 120;
  private String zone = java.time.ZoneId.systemDefault().getId();
  private List<String> excludedProcesses = List.of("KeePassXC.exe", "1Password.exe");
  private List<String> excludedWindowTitlePatterns = List.of("*Password*", "*Private Browsing*", "*InPrivate*");

  public void validate() {
    if (captureIntervalSeconds < 1 || screenshotRetentionDays < 0 || sessionGapSeconds < 0
        || (summaryGapSeconds != null && summaryGapSeconds < 0) || summaryBriefSwitchSeconds < 0
        || summaryNormalMergeGapSeconds < 0 || summaryMaximumMergeGapSeconds < summaryNormalMergeGapSeconds
        || !Double.isFinite(primaryConfidenceThreshold) || primaryConfidenceThreshold < 0 || primaryConfidenceThreshold > 1
        || !Double.isFinite(changeThreshold) || changeThreshold < 0 || changeThreshold > 1
        || excludedProcesses == null || excludedWindowTitlePatterns == null)
      throw new IllegalArgumentException("Invalid rei.activity settings");
    java.time.ZoneId.of(zone);
  }
  public int effectiveMaximumGapSeconds() {return summaryGapSeconds==null?summaryMaximumMergeGapSeconds:Math.min(summaryGapSeconds,summaryMaximumMergeGapSeconds);}
  public int effectiveNormalGapSeconds() {return Math.min(summaryNormalMergeGapSeconds,effectiveMaximumGapSeconds());}
}
