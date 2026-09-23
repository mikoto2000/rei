package dev.mikoto2000.rei.activity;

import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(ActivityProperties.class)
public class ActivityConfiguration {
  @Bean ActivityStore activityStore(javax.sql.DataSource ds,ActivityProperties p) {
    p.validate();return new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(p.getSessionGapSeconds()),ZoneId.of(p.getZone())));
  }
  @Bean ScreenshotStore activityScreenshots() { return new FileScreenshotStore(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("activity/screenshots")); }
  @Bean DesktopActivityObserver activityObserver() { return new WindowsDesktopActivityObserver(); }
  @Bean ActivityExtractor activityExtractor(dev.mikoto2000.rei.llm.LlmModelProvider provider,dev.mikoto2000.rei.core.service.ModelHolderService current,ActivityProperties properties) {
    return new VisionActivityExtractor(() -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
        () -> provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY,current.get()),properties.getVisionImageScale());
  }
  @Bean ActivityCapture activityCapture(ActivityProperties p,DesktopActivityObserver observer,ActivityExtractor extractor,ActivityStore store,ScreenshotStore screenshots,
      @Qualifier("activityAnalysisExecutor") ThreadPoolTaskExecutor analysisExecutor,
      @Qualifier("activityBackgroundExecutor") ThreadPoolTaskExecutor backgroundExecutor) {
    return new ActivityCapture(p,observer,extractor,store,screenshots,Clock.systemUTC(),analysisExecutor,backgroundExecutor);
  }
  @Bean ActivityTimeline activityTimeline(ActivityStore store,ActivityProperties p) {
    var zone=ZoneId.of(p.getZone());
    return new ActivityTimeline(store,Clock.system(zone),new SemanticSessionPolicy(
        Duration.ofSeconds(p.effectiveNormalGapSeconds()),Duration.ofSeconds(p.effectiveMaximumGapSeconds()),
        Duration.ofSeconds(p.getSummaryBriefSwitchSeconds()),p.getPrimaryConfidenceThreshold(),zone));
  }
  @Bean ActivityTools activityTools(ActivityTimeline timeline) {return new ActivityTools(timeline);}
  @Bean ThreadPoolTaskExecutor activityExecutor() {
    return worker("rei-activity-observe-");
  }
  @Bean ThreadPoolTaskExecutor activityAnalysisExecutor() {
    var executor=worker("rei-activity-analyze-");
    // A drain may relinquish ownership just before its Runnable returns. One slot covers that handoff.
    executor.setQueueCapacity(1);return executor;
  }
  @Bean ThreadPoolTaskExecutor activityBackgroundExecutor() {
    var executor=worker("rei-activity-background-");
    executor.setQueueCapacity(1);return executor;
  }
  private static ThreadPoolTaskExecutor worker(String name) {
    var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);
    executor.setThreadNamePrefix(name);executor.setDaemon(true);executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(5);
    return executor;
  }
  @Bean ActivityJob activityJob(ActivityCapture capture,@Qualifier("activityExecutor") ThreadPoolTaskExecutor executor) {return new ActivityJob(capture,executor);}
  public record ActivityJob(ActivityCapture capture,ThreadPoolTaskExecutor executor) implements AutoCloseable {
    @Override public void close() { capture.close(); }
    @Scheduled(fixedDelayString="#{${rei.activity.capture-interval-seconds:60} * 1000}",initialDelayString="#{${rei.activity.capture-interval-seconds:60} * 1000}")
    public void poll() {
      try {executor.execute(capture::tick);}catch(org.springframework.core.task.TaskRejectedException ignored) { /* Single worker is busy or shutting down; never queue screenshots. */ }
    }
  }
}
