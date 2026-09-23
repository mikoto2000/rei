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
  private double visionImageScale = .5;
  private int backgroundAnalysisIntervalSeconds = 300;
  private Detection detection = new Detection();
  private Classification classification=new Classification();
  @Data public static class Classification {
    private String userRulesFile="activity/classification-rules.yaml";
    private boolean hotReload=true;
    private Registry unknownRegistry=new Registry();
    private Registry entertainmentRegistry=new Registry();
    private RuleSuggestion ruleSuggestion=new RuleSuggestion();
    private Diagnostics diagnostics=new Diagnostics();
  }
  @Data public static class Registry { private boolean enabled=true;private int retentionDays=30;private int maxEntries=1000; }
  @Data public static class RuleSuggestion { private boolean enabled=true;private int minimumSamples=3; }
  @Data public static class Diagnostics { private boolean enabled=true; }
  public enum DetectionMode { EVIDENCE_FIRST, VISION_FIRST }
  @Data public static class Detection {
    private DetectionMode mode=DetectionMode.EVIDENCE_FIRST;
    private boolean evidenceEnabled=true;
    private boolean visionEnabled=true;
    private boolean fallbackEnabled=true;
    private double skipVisionConfidence=.8;
    private boolean foregroundCrop=true;
    private boolean backgroundFullScreenEnabled=false;
    private int maxOutputTokens=2048;
  }
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
    if(classification==null || classification.userRulesFile==null || classification.userRulesFile.isBlank() || classification.unknownRegistry==null || classification.entertainmentRegistry==null || classification.ruleSuggestion==null || classification.diagnostics==null || classification.ruleSuggestion.minimumSamples<1)
      throw new IllegalArgumentException("Invalid classification toolkit settings");
    for(var registry:List.of(classification.unknownRegistry,classification.entertainmentRegistry))if(registry.retentionDays<1 || registry.maxEntries<1 || registry.maxEntries>10000)throw new IllegalArgumentException("Invalid registry retention");
    if(detection==null || detection.mode==null || !Double.isFinite(detection.skipVisionConfidence)
        || detection.skipVisionConfidence<0 || detection.skipVisionConfidence>1 || detection.maxOutputTokens<1)
      throw new IllegalArgumentException("Invalid activity detection settings");
    if (captureIntervalSeconds < 1 || backgroundAnalysisIntervalSeconds < 1 || screenshotRetentionDays < 0 || sessionGapSeconds < 0
        || !Double.isFinite(visionImageScale) || visionImageScale<=0 || visionImageScale>1
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
