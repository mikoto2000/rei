package dev.mikoto2000.rei.activity.behavior;

import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("rei.activity.behavior")
public class BehaviorProperties {
  private boolean enabled=false;
  private Set<String> entertainmentCategories=Set.of("social","media","shopping","gaming");
  private Continuous continuous=new Continuous();
  private Windows windows=new Windows();
  private Interruption interruption=new Interruption();
  private Cooldown cooldown=new Cooldown();
  private int checkIntervalSeconds=60;
  private int historyMinutes=1440;
  @Data public static class Continuous {private int noticeMinutes=30,warningMinutes=60,strongWarningMinutes=120;}
  @Data public static class Window {
    private int durationMinutes,minimumObservedMinutes;private double ratio;
    public Window() {}
    public Window(int duration,int minimum,double ratio) {this.durationMinutes=duration;this.minimumObservedMinutes=minimum;this.ratio=ratio;}
  }
  @Data public static class Windows {private Window shortWindow=new Window(60,30,.5),longWindow=new Window(120,60,.6);}
  @Data public static class Interruption {private int resetAfterWorkMinutes=5,noiseToleranceSeconds=60;}
  @Data public static class Cooldown {private int noticeMinutes=60,warningMinutes=45,strongWarningMinutes=30;}
  public void validate() {
    if(entertainmentCategories==null || entertainmentCategories.isEmpty() || !Set.of("social","media","shopping","gaming").containsAll(entertainmentCategories)
        || continuous==null || windows==null || windows.shortWindow==null || windows.longWindow==null || interruption==null || cooldown==null || checkIntervalSeconds<1
        || historyMinutes<1 || historyMinutes>10080) throw new IllegalArgumentException("Invalid behavior settings");
    if(continuous.noticeMinutes<1 || continuous.warningMinutes<continuous.noticeMinutes || continuous.strongWarningMinutes<continuous.warningMinutes
        || interruption.resetAfterWorkMinutes<1 || interruption.noiseToleranceSeconds<0
        || cooldown.noticeMinutes<1 || cooldown.warningMinutes<1 || cooldown.strongWarningMinutes<1) throw new IllegalArgumentException("Invalid behavior thresholds");
    for(var w:java.util.List.of(windows.shortWindow,windows.longWindow)) {
      if(w.durationMinutes<1 || w.minimumObservedMinutes<1 || w.minimumObservedMinutes>w.durationMinutes
          || !Double.isFinite(w.ratio) || w.ratio<=0 || w.ratio>1 || historyMinutes<w.durationMinutes) throw new IllegalArgumentException("Invalid behavior window");
    }
    if(historyMinutes<continuous.strongWarningMinutes || historyMinutes<interruption.resetAfterWorkMinutes) throw new IllegalArgumentException("Behavior history is too short");
  }
  public long cooldownSeconds(BehaviorSeverity s) {return 60L*switch(s) {case NONE->0;case NOTICE->cooldown.noticeMinutes;case WARNING->cooldown.warningMinutes;case STRONG_WARNING->cooldown.strongWarningMinutes;};}
}
