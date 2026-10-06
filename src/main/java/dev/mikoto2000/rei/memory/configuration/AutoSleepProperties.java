package dev.mikoto2000.rei.memory.configuration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.memory.auto-sleep")
public record AutoSleepProperties(boolean enabled, Duration minimumIdle, int minimumTurns, Duration retryInterval, String cron, String zone,
    boolean onSessionEnd,boolean onShutdown) {
  public AutoSleepProperties(boolean enabled,Duration minimumIdle,int minimumTurns,Duration retryInterval,String cron,String zone) {
    this(enabled,minimumIdle,minimumTurns,retryInterval,cron,zone,false,false);
  }
  public AutoSleepProperties(boolean enabled,Duration minimumIdle,int minimumTurns,Duration retryInterval) {
    this(enabled,minimumIdle,minimumTurns,retryInterval,null,null);
  }
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public AutoSleepProperties {
    if(cron!=null&&cron.isBlank())cron=null;
    if(zone==null||zone.isBlank())zone=java.time.ZoneId.systemDefault().getId();
    try {java.time.ZoneId.of(zone);}
    catch(java.time.DateTimeException invalid) {throw new IllegalArgumentException("Invalid Auto Sleep time zone",invalid);}
    if(cron!=null) {
      if(cron.length()>256)throw new IllegalArgumentException("Auto Sleep cron is too long");
      org.springframework.scheduling.support.CronExpression.parse(cron);
    }
    if (minimumIdle == null) minimumIdle=Duration.ofMinutes(5);
    if (retryInterval == null) retryInterval=Duration.ofMinutes(10);
    if (minimumTurns == 0) minimumTurns=5;
    if (minimumIdle.isNegative() || minimumIdle.isZero() || retryInterval.isNegative()
        || retryInterval.isZero() || minimumTurns < 1) throw new IllegalArgumentException("Invalid Auto Sleep limits");
  }
}
