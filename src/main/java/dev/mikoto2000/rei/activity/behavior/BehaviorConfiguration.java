package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.ActivityCapture;
import dev.mikoto2000.rei.activity.ActivityProperties;
import dev.mikoto2000.rei.activity.ActivityStore;
import dev.mikoto2000.rei.llm.LlmFeature;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import dev.mikoto2000.rei.topic.AgentActivityTracker;
import dev.mikoto2000.rei.topic.AgentMessagePublisher;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 作業習慣の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BehaviorProperties.class)
public class BehaviorConfiguration {
  /** 作業習慣の支援で用いる作業習慣状態保存先を返す。 */
  @Bean
  BehaviorStateStore behaviorStateStore(javax.sql.DataSource ds) {
    return new SqliteBehaviorStateStore(ds);
  }

  /** 作業習慣の支援で用いる作業習慣メッセージ生成を返す。 */
  @Bean
  BehaviorMessageGenerator behaviorMessageGenerator(
      LlmModelProvider provider,
      dev.mikoto2000.rei.core.service.ModelHolderService model,
      dev.mikoto2000.rei.core.configuration.SystemPromptService system) {
    return new LlmBehaviorMessageGenerator(
        () -> provider.chatModel(LlmFeature.ACTIVITY_BEHAVIOR),
        () -> provider.chatOptions(LlmFeature.ACTIVITY_BEHAVIOR, model.get()),
        system::systemPrompt);
  }

  /** 作業習慣の支援で用いる作業習慣サービスを返す。 */
  @Bean
  BehaviorService behaviorService(
      BehaviorProperties config,
      ActivityProperties activity,
      ActivityCapture capture,
      ActivityStore store,
      BehaviorStateStore state,
      BehaviorMessageGenerator generator,
      AgentMessagePublisher publisher,
      AgentActivityTracker tracker) {
    return new BehaviorService(
        config, activity, capture, store, state, generator, publisher, tracker, Clock.systemUTC());
  }

  /** 作業習慣の支援で用いる作業習慣実行先を返す。 */
  @Bean
  ThreadPoolTaskExecutor behaviorExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("rei-behavior-");
    executor.setDaemon(true);
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  /** 作業習慣の支援で用いる作業習慣実行する作業を返す。 */
  @Bean
  BehaviorJob behaviorJob(
      BehaviorService service, @Qualifier("behaviorExecutor") ThreadPoolTaskExecutor executor) {
    return new BehaviorJob(service, executor);
  }

  /**
   * 作業習慣の支援で受け渡す作業習慣実行する作業を表す。
   *
   * @param service サービス
   * @param executor 実行先
   */
  public record BehaviorJob(BehaviorService service, ThreadPoolTaskExecutor executor) {
    /** 作業習慣の支援に関する状態確認を処理する。 */
    @Scheduled(
        fixedDelayString = "#{${rei.activity.behavior.check-interval-seconds:60} * 1000}",
        initialDelayString = "#{${rei.activity.behavior.check-interval-seconds:60} * 1000}")
    public void poll() {
      if (!service.enabled()) return;
      try {
        executor.execute(service::tick);
      } catch (org.springframework.core.task.TaskRejectedException ignored) {
        /* Never queue stale notifications. */
      }
    }
  }
}
