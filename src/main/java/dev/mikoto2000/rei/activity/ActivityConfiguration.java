package dev.mikoto2000.rei.activity;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 作業状況の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ActivityProperties.class)
public class ActivityConfiguration {
  /** 作業状況の把握で用いる分類補助操作を返す。 */
  @Bean
  ClassificationToolkit classificationToolkit(javax.sql.DataSource ds, ActivityProperties p) {
    p.validate();
    return new ClassificationToolkit(
        p,
        new OperationalRules(
            dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                .resolve(p.getClassification().getUserRulesFile())),
        new ClassificationTelemetryRepository(ds),
        Clock.systemUTC());
  }

  /** 作業状況の把握で用いる作業状況保存先を返す。 */
  @Bean
  ActivityStore activityStore(
      javax.sql.DataSource ds, ActivityProperties p, ClassificationToolkit toolkit) {
    p.validate();
    return toolkit.wrap(
        new SqliteActivityStore(
            ds,
            new SessionMergePolicy(
                Duration.ofSeconds(p.getSessionGapSeconds()), ZoneId.of(p.getZone()))));
  }

  /** 作業状況の把握で用いる作業状況画面画像一覧を返す。 */
  @Bean
  ScreenshotStore activityScreenshots() {
    return new FileScreenshotStore(
        dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
            .resolve("activity/screenshots"));
  }

  /** 作業状況の把握で用いる作業状況観測を返す。 */
  @Bean
  DesktopActivityObserver activityObserver(ActivityProperties p) {
    return new WindowsDesktopActivityObserver(p);
  }

  /** 作業状況の把握で用いる作業状況情報の抽出を返す。 */
  @Bean
  ActivityExtractor activityExtractor(
      dev.mikoto2000.rei.llm.LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService current,
      ActivityProperties properties) {
    return new VisionActivityExtractor(
        () -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
        () -> provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY, current.get()),
        properties.getVisionImageScale(),
        properties.getDetection().getMaxOutputTokens());
  }

  /** 作業状況の把握で用いる作業状況エージェント根拠を返す。 */
  @Bean
  ActivityAgentEvidenceSource activityAgentEvidence(
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventBus> bus,
      ActivityProperties p) {
    return new ActivityAgentEvidenceSource(
        bus.getIfAvailable(), () -> p.isEnabled() && p.getTemporal().isEnabled());
  }

  /** 作業状況の把握で用いる作業状況プロジェクト根拠を返す。 */
  @Bean
  ActivityEvidenceSource activityProjectEvidence(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.core.project.ProjectService>
          projects,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.workcontext.WorkContextRepository>
          contexts,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.workcontext.WorkContextGit>
          git,
      ActivityProperties properties) {
    return new ActivityObservationContextSource(
        properties,
        () -> {
          var service = projects.getIfAvailable();
          return service == null ? null : service.currentContext();
        },
        project -> {
          var repository = contexts.getIfAvailable();
          return repository == null ? java.util.Optional.empty() : repository.current(project);
        },
        (root, at) -> {
          var capture = git.getIfAvailable();
          return capture == null ? null : capture.capture(root, at);
        });
  }

  /** 作業状況の把握で用いる作業状況取得記録を返す。 */
  @Bean
  ActivityCapture activityCapture(
      ActivityProperties p,
      DesktopActivityObserver observer,
      ActivityExtractor extractor,
      ActivityStore store,
      ScreenshotStore screenshots,
      @Qualifier("activityAnalysisExecutor") ThreadPoolTaskExecutor analysisExecutor,
      @Qualifier("activityBackgroundExecutor") ThreadPoolTaskExecutor backgroundExecutor,
      java.util.List<ActivityEvidenceSource> sources,
      ClassificationToolkit toolkit) {
    var capture =
        new ActivityCapture(
            p,
            observer,
            extractor,
            store,
            screenshots,
            Clock.systemUTC(),
            analysisExecutor,
            backgroundExecutor,
            sources);
    capture.useToolkit(toolkit);
    return capture;
  }

  /** 作業状況の把握で用いる日次要約サービスを返す。 */
  @Bean
  DailySummaryService dailySummaryService(
      ActivityProperties p,
      dev.mikoto2000.rei.llm.LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService current) {
    var aliases =
        new ProjectAliasStore(
            dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
                .resolve(p.getSummary().getProjectAliasesFile()));
    DailySummaryWriter writer =
        p.getSummary().isLlmEnabled()
            ? new LlmDailySummaryWriter(
                () -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
                () ->
                    provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY, current.get()),
                Duration.ofSeconds(p.getSummary().getTimeoutSeconds()))
            : null;
    return new DailySummaryService(aliases, writer);
  }

  /** 作業状況の把握で用いる作業状況時系列の記録を返す。 */
  @Bean
  ActivityTimeline activityTimeline(
      ActivityStore store,
      ActivityProperties p,
      DailySummaryService dailySummary,
      PeriodCoachingStore scoreCriteria,
      WorkActivityInferenceStore inferences) {
    var zone = ZoneId.of(p.getZone());
    return new ActivityTimeline(
        store,
        Clock.system(zone),
        new SemanticSessionPolicy(
            Duration.ofSeconds(p.effectiveNormalGapSeconds()),
            Duration.ofSeconds(p.effectiveMaximumGapSeconds()),
            Duration.ofSeconds(p.getSummaryBriefSwitchSeconds()),
            p.getPrimaryConfidenceThreshold(),
            zone),
        dailySummary,
        scoreCriteria,
        p.getTemporal().isEnabled() ? inferences : null);
  }

  /** 作業状況の把握で用いる作業作業状況推定保存先を返す。 */
  @Bean
  WorkActivityInferenceStore workActivityInferenceStore(javax.sql.DataSource ds) {
    return new WorkActivityInferenceStore(ds);
  }

  /** 作業状況の把握で用いる時系列作業状況推定を返す。 */
  @Bean
  TemporalActivityInferenceService temporalActivityInference(
      ActivityProperties p,
      ActivityStore store,
      WorkActivityInferenceStore inferences,
      ActivityAgentEvidenceSource executions,
      ActivityCapture capture,
      dev.mikoto2000.rei.llm.LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService current) {
    var clock = Clock.systemUTC();
    var model =
        p.getTemporal().isLlmEnabled()
            ? new LlmTemporalActivityModel(
                () -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
                () ->
                    provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY, current.get()))
            : null;
    return new TemporalActivityInferenceService(
        p,
        store,
        inferences,
        model,
        clock,
        () -> executions.executions(clock.instant(), p.getTemporal().getWindowSeconds()),
        capture::isPaused);
  }

  /** 作業状況の把握で用いる作業状況時系列実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor activityTemporalExecutor() {
    return worker("rei-activity-temporal-");
  }

  /** 作業状況の把握で用いる時系列作業状況実行する作業を返す。 */
  @Bean
  TemporalActivityJob temporalActivityJob(
      TemporalActivityInferenceService service,
      @Qualifier("activityTemporalExecutor") ThreadPoolTaskExecutor executor) {
    return new TemporalActivityJob(service, executor);
  }

  /**
   * 作業状況の把握で受け渡す時系列作業状況実行する作業を表す。
   *
   * @param service サービス
   * @param executor 実行先
   */
  public record TemporalActivityJob(
      TemporalActivityInferenceService service, ThreadPoolTaskExecutor executor) {
    /** 作業状況の把握に関する状態確認を処理する。 */
    @Scheduled(
        fixedDelayString = "#{${rei.activity.temporal.interval-seconds:60} * 1000}",
        initialDelayString = "#{${rei.activity.temporal.interval-seconds:60} * 1000}")
    public void poll() {
      try {
        executor.execute(service::tick);
      } catch (org.springframework.core.task.TaskRejectedException ignored) {
        /* No stale inference queue. */
      }
    }
  }

  /** 作業状況の把握で用いる作業状況ツール一覧を返す。 */
  @Bean
  ActivityTools activityTools(ActivityTimeline timeline) {
    return new ActivityTools(timeline);
  }

  /** 作業状況の把握で用いる期間助言保存先を返す。 */
  @Bean
  PeriodCoachingStore periodCoachingStore(javax.sql.DataSource ds) {
    return new SqlitePeriodCoachingStore(ds);
  }

  /** 作業状況の把握で用いる期間助言サービスを返す。 */
  @Bean
  PeriodCoachingService periodCoachingService(
      ActivityTimeline timeline, PeriodCoachingStore store) {
    return new PeriodCoachingService(timeline, store, Clock.systemUTC());
  }

  /** 作業状況の把握で用いる自動期間助言を返す。 */
  @Bean
  AutomaticPeriodCoaching automaticPeriodCoaching(
      ActivityProperties properties,
      PeriodCoachingService coaching,
      ActivityCapture capture,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.topic.AgentMessagePublisher>
          publisher,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.topic.AgentActivityTracker>
          tracker) {
    return new AutomaticPeriodCoaching(
        properties,
        coaching,
        publisher.getIfAvailable(),
        tracker.getIfAvailable(),
        capture::isPaused,
        Clock.systemUTC());
  }

  /** 作業状況の把握で用いる期間助言実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor periodCoachingExecutor() {
    return worker("rei-period-coaching-");
  }

  /** 作業状況の把握で用いる期間助言実行する作業を返す。 */
  @Bean
  PeriodCoachingJob periodCoachingJob(
      AutomaticPeriodCoaching service,
      @Qualifier("periodCoachingExecutor") ThreadPoolTaskExecutor executor) {
    return new PeriodCoachingJob(service, executor);
  }

  /**
   * 作業状況の把握で受け渡す期間助言実行する作業を表す。
   *
   * @param service サービス
   * @param executor 実行先
   */
  public record PeriodCoachingJob(
      AutomaticPeriodCoaching service, ThreadPoolTaskExecutor executor) {
    /** 作業状況の把握に関する状態確認を処理する。 */
    @Scheduled(
        fixedDelayString = "#{${rei.activity.coaching.check-interval-seconds:3600} * 1000}",
        initialDelayString = "#{${rei.activity.coaching.check-interval-seconds:3600} * 1000}")
    public void poll() {
      if (!service.enabled()) return;
      try {
        executor.execute(service::tick);
      } catch (org.springframework.core.task.TaskRejectedException ignored) {
        /* Never queue stale advice. */
      }
    }
  }

  /** 作業状況の把握で用いる作業状況作業文脈サービスを返す。 */
  @Bean
  ActivityWorkContextService activityWorkContextService(
      ActivityStore store,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.workcontext.WorkContextRepository>
          contexts,
      ActivityProperties properties) {
    return new ActivityWorkContextService(
        store,
        project -> {
          var repository = contexts.getIfAvailable();
          return repository == null ? java.util.List.of() : repository.history(project, 100);
        },
        Clock.system(ZoneId.of(properties.getZone())));
  }

  /** 作業状況の把握で用いる作業状況時系列の記録Presentationを返す。 */
  @Bean
  ActivityTimelinePresentationService activityTimelinePresentation(
      ActivityTimeline timeline,
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.activity.behavior.BehaviorStateStore>
          behavior) {
    return new ActivityTimelinePresentationService(timeline, behavior.getIfAvailable());
  }

  /** 作業状況の把握で用いる分類規則提案一覧を返す。 */
  @Bean
  ClassificationRuleSuggestions classificationRuleSuggestions(
      ClassificationToolkit toolkit,
      dev.mikoto2000.rei.llm.LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService current) {
    return new ClassificationRuleSuggestions(
        toolkit,
        new LlmClassificationRuleModel(
            () -> provider.chatModel(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY),
            () -> provider.chatOptions(dev.mikoto2000.rei.llm.LlmFeature.ACTIVITY, current.get()),
            toolkit.properties().getClassification().getRuleSuggestion().getMaxOutputTokens()));
  }

  /** 作業状況の把握で用いる作業状況実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor activityExecutor() {
    return worker("rei-activity-observe-");
  }

  /** 作業状況の把握で用いる作業状況分析実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor activityAnalysisExecutor() {
    var executor = worker("rei-activity-analyze-");
    // A drain may relinquish ownership just before its Runnable returns. One slot covers that
    // handoff.
    executor.setQueueCapacity(1);
    return executor;
  }

  /** 作業状況の把握で用いる作業状況バックグラウンド実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor activityBackgroundExecutor() {
    var executor = worker("rei-activity-background-");
    executor.setQueueCapacity(1);
    return executor;
  }

  /** 作業状況の把握で用いる実行の担当を返す。 */
  private static ThreadPoolTaskExecutor worker(String name) {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix(name);
    executor.setDaemon(true);
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  /** 作業状況の把握で用いる作業状況実行する作業を返す。 */
  @Bean
  ActivityJob activityJob(
      ActivityCapture capture, @Qualifier("activityExecutor") ThreadPoolTaskExecutor executor) {
    return new ActivityJob(capture, executor);
  }

  /** 作業状況の把握で用いる作業状況観測内容通知一覧を返す。 */
  @Bean(destroyMethod = "close")
  AutoCloseable activityObservationSignals(
      ActivityCapture capture,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.event.AgentEventBus>
          buses,
      org.springframework.beans.factory.ObjectProvider<dev.mikoto2000.rei.voice.VoiceEventPublisher>
          voices) {
    var bus = buses.getIfAvailable();
    var voice = voices.getIfAvailable();
    var agent =
        bus == null
            ? null
            : bus.subscribe(
                e -> {
                  if (e.type() == dev.mikoto2000.rei.event.AgentEventType.COMPUTER_USE_PROGRESS)
                    capture.forceObservation();
                });
    var audio =
        voice == null
            ? null
            : voice.subscribe(
                e -> {
                  switch (e.type()) {
                    case STATE_CHANGED, CAPTURE_RESUMED, CAPTURE_FAILED, REVIEW_REQUIRED ->
                        capture.forceObservation();
                    default -> {} // Do not collect recognition text or audio.
                  }
                });
    return () -> {
      if (agent != null) agent.unsubscribe();
      if (audio != null) audio.close();
    };
  }

  /** 作業状況の把握で用いる分類再読み込み実行する作業を返す。 */
  @Bean
  ClassificationReloadJob classificationReloadJob(ClassificationToolkit toolkit) {
    return new ClassificationReloadJob(toolkit);
  }

  /**
   * 作業状況の把握で受け渡す分類再読み込み実行する作業を表す。
   *
   * @param toolkit 補助操作
   */
  public record ClassificationReloadJob(ClassificationToolkit toolkit) {
    /** 作業状況の把握に関する状態確認を処理する。 */
    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void poll() {
      toolkit.poll();
    }
  }

  /**
   * 作業状況の把握で受け渡す作業状況実行する作業を表す。
   *
   * @param capture 取得記録
   * @param executor 実行先
   */
  public record ActivityJob(ActivityCapture capture, ThreadPoolTaskExecutor executor)
      implements AutoCloseable {
    /** 作業状況の把握で利用した資源や登録を解放し、利用を終了する。 */
    @Override
    public void close() {
      capture.close();
    }

    /** 作業状況の把握に関する状態確認を処理する。 */
    @Scheduled(
        fixedDelayString =
            "#{${rei.activity.observation.input-aware-enabled:true} ? ${rei.activity.observation.interval-seconds:15} * 1000 : ${rei.activity.capture-interval-seconds:60} * 1000}",
        initialDelayString =
            "#{${rei.activity.observation.input-aware-enabled:true} ? ${rei.activity.observation.interval-seconds:15} * 1000 : ${rei.activity.capture-interval-seconds:60} * 1000}")
    public void poll() {
      try {
        executor.execute(capture::tick);
      } catch (org.springframework.core.task.TaskRejectedException ignored) {
        /* Single worker is busy or shutting down; never queue screenshots. */
      }
    }
  }
}
