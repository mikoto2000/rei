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
  @Bean ClassificationToolkit classificationToolkit(javax.sql.DataSource ds,ActivityProperties p) {
    p.validate();return new ClassificationToolkit(p,new OperationalRules(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve(p.getClassification().getUserRulesFile())),new ClassificationTelemetryRepository(ds),Clock.systemUTC());
  }
  @Bean ActivityStore activityStore(javax.sql.DataSource ds,ActivityProperties p,ClassificationToolkit toolkit) {
    p.validate();return toolkit.wrap(new SqliteActivityStore(ds,new SessionMergePolicy(Duration.ofSeconds(p.getSessionGapSeconds()),ZoneId.of(p.getZone()))));
  }
  @Bean ScreenshotStore activityScreenshots() { return new FileScreenshotStore(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("activity/screenshots")); }
  @Bean DesktopActivityObserver activityObserver() { return new WindowsDesktopActivityObserver(); }
  @Bean ActivityExtractor activityExtractor(dev.mikoto2000.rei.llm.LlmModelProvider provider,dev.mikoto2000.rei.core.service.ModelHolderService current,ActivityProperties properties) {
    return new VisionActivityExtractor(() -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
        () -> provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY,current.get()),properties.getVisionImageScale(),properties.getDetection().getMaxOutputTokens());
  }
  @Bean ActivityAgentEvidenceSource activityAgentEvidence(org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventBus> bus) {
    return new ActivityAgentEvidenceSource(bus.getIfAvailable());
  }
  @Bean ActivityEvidenceSource activityProjectEvidence(org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.core.project.ProjectService> projects,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextRepository> contexts,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextGit> git,ActivityProperties properties) {
    return new ActivityObservationContextSource(properties,()->{var service=projects.getIfAvailable();return service==null?null:service.currentContext();},
        project->{var repository=contexts.getIfAvailable();return repository==null?java.util.Optional.empty():repository.current(project);},
        (root,at)->{var capture=git.getIfAvailable();return capture==null?null:capture.capture(root,at);});
  }
  @Bean ActivityCapture activityCapture(ActivityProperties p,DesktopActivityObserver observer,ActivityExtractor extractor,ActivityStore store,ScreenshotStore screenshots,
      @Qualifier("activityAnalysisExecutor") ThreadPoolTaskExecutor analysisExecutor,
      @Qualifier("activityBackgroundExecutor") ThreadPoolTaskExecutor backgroundExecutor,java.util.List<ActivityEvidenceSource> sources,ClassificationToolkit toolkit) {
    var capture=new ActivityCapture(p,observer,extractor,store,screenshots,Clock.systemUTC(),analysisExecutor,backgroundExecutor,sources);capture.useToolkit(toolkit);return capture;
  }
  @Bean DailySummaryService dailySummaryService(ActivityProperties p,dev.mikoto2000.rei.llm.LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService current) {
    var aliases=new ProjectAliasStore(dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve(p.getSummary().getProjectAliasesFile()));
    DailySummaryWriter writer=p.getSummary().isLlmEnabled()?new LlmDailySummaryWriter(
        ()->provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
        ()->provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY,current.get()),Duration.ofSeconds(p.getSummary().getTimeoutSeconds())):null;
    return new DailySummaryService(aliases,writer);
  }
  @Bean ActivityTimeline activityTimeline(ActivityStore store,ActivityProperties p,DailySummaryService dailySummary,PeriodCoachingStore scoreCriteria) {
    var zone=ZoneId.of(p.getZone());
    return new ActivityTimeline(store,Clock.system(zone),new SemanticSessionPolicy(
        Duration.ofSeconds(p.effectiveNormalGapSeconds()),Duration.ofSeconds(p.effectiveMaximumGapSeconds()),
        Duration.ofSeconds(p.getSummaryBriefSwitchSeconds()),p.getPrimaryConfidenceThreshold(),zone),dailySummary,scoreCriteria);
  }
  @Bean ActivityTools activityTools(ActivityTimeline timeline) {return new ActivityTools(timeline);}
  @Bean PeriodCoachingStore periodCoachingStore(javax.sql.DataSource ds){return new SqlitePeriodCoachingStore(ds);}
  @Bean PeriodCoachingService periodCoachingService(ActivityTimeline timeline,PeriodCoachingStore store){return new PeriodCoachingService(timeline,store,Clock.systemUTC());}
  @Bean AutomaticPeriodCoaching automaticPeriodCoaching(ActivityProperties properties,PeriodCoachingService coaching,ActivityCapture capture,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.topic.AgentMessagePublisher> publisher,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.topic.AgentActivityTracker> tracker) {
    return new AutomaticPeriodCoaching(properties,coaching,publisher.getIfAvailable(),tracker.getIfAvailable(),capture::isPaused,Clock.systemUTC());
  }
  @Bean ThreadPoolTaskExecutor periodCoachingExecutor(){return worker("rei-period-coaching-");}
  @Bean PeriodCoachingJob periodCoachingJob(AutomaticPeriodCoaching service,@Qualifier("periodCoachingExecutor") ThreadPoolTaskExecutor executor){return new PeriodCoachingJob(service,executor);}
  public record PeriodCoachingJob(AutomaticPeriodCoaching service,ThreadPoolTaskExecutor executor) {
    @Scheduled(fixedDelayString="#{${rei.activity.coaching.check-interval-seconds:3600} * 1000}",initialDelayString="#{${rei.activity.coaching.check-interval-seconds:3600} * 1000}")
    public void poll(){if(!service.enabled())return;try{executor.execute(service::tick);}catch(org.springframework.core.task.TaskRejectedException ignored){/* Never queue stale advice. */}}
  }
  @Bean ActivityWorkContextService activityWorkContextService(ActivityStore store,org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.workcontext.WorkContextRepository> contexts,ActivityProperties properties) {
    return new ActivityWorkContextService(store,project->{
      var repository=contexts.getIfAvailable();return repository==null?java.util.List.of():repository.history(project,100);
    },Clock.system(ZoneId.of(properties.getZone())));
  }
  @Bean ActivityTimelinePresentationService activityTimelinePresentation(ActivityTimeline timeline,org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.activity.behavior.BehaviorStateStore> behavior) {
    return new ActivityTimelinePresentationService(timeline,behavior.getIfAvailable());
  }
  @Bean ClassificationRuleSuggestions classificationRuleSuggestions(ClassificationToolkit toolkit,dev.mikoto2000.rei.llm.LlmModelProvider provider,dev.mikoto2000.rei.core.service.ModelHolderService current) {
    return new ClassificationRuleSuggestions(toolkit,new LlmClassificationRuleModel(()->provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),()->provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY,current.get()),toolkit.properties().getClassification().getRuleSuggestion().getMaxOutputTokens()));
  }
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
  @Bean(destroyMethod="close") AutoCloseable activityObservationSignals(ActivityCapture capture,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventBus> buses,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.voice.VoiceEventPublisher> voices) {
    var bus=buses.getIfAvailable();var voice=voices.getIfAvailable();
    var agent=bus==null?null:bus.subscribe(e->{if(e.type()==dev.mikoto2000.rei.event.AgentEventType.COMPUTER_USE_PROGRESS)capture.forceObservation();});
    var audio=voice==null?null:voice.subscribe(e->{switch(e.type()) {
      case STATE_CHANGED,CAPTURE_RESUMED,CAPTURE_FAILED,REVIEW_REQUIRED -> capture.forceObservation();
      default -> {} // Do not collect recognition text or audio.
    }});
    return ()->{if(agent!=null)agent.unsubscribe();if(audio!=null)audio.close();};
  }
  @Bean ClassificationReloadJob classificationReloadJob(ClassificationToolkit toolkit){return new ClassificationReloadJob(toolkit);}
  public record ClassificationReloadJob(ClassificationToolkit toolkit) {
    @Scheduled(fixedDelay=3000,initialDelay=3000) public void poll(){toolkit.poll();}
  }
  public record ActivityJob(ActivityCapture capture,ThreadPoolTaskExecutor executor) implements AutoCloseable {
    @Override public void close() { capture.close(); }
    @Scheduled(fixedDelayString="#{${rei.activity.observation.input-aware-enabled:true} ? ${rei.activity.observation.interval-seconds:15} * 1000 : ${rei.activity.capture-interval-seconds:60} * 1000}",initialDelayString="#{${rei.activity.observation.input-aware-enabled:true} ? ${rei.activity.observation.interval-seconds:15} * 1000 : ${rei.activity.capture-interval-seconds:60} * 1000}")
    public void poll() {
      try {executor.execute(capture::tick);}catch(org.springframework.core.task.TaskRejectedException ignored) { /* Single worker is busy or shutting down; never queue screenshots. */ }
    }
  }
}
