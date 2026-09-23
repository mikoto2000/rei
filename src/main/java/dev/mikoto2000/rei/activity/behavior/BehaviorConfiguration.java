package dev.mikoto2000.rei.activity.behavior;

import dev.mikoto2000.rei.activity.*;
import dev.mikoto2000.rei.topic.*;
import dev.mikoto2000.rei.llm.*;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(BehaviorProperties.class)
public class BehaviorConfiguration {
  @Bean BehaviorStateStore behaviorStateStore(javax.sql.DataSource ds) {return new SqliteBehaviorStateStore(ds);}
  @Bean BehaviorMessageGenerator behaviorMessageGenerator(LlmModelProvider provider,dev.mikoto2000.rei.core.service.ModelHolderService model,
      dev.mikoto2000.rei.core.configuration.SystemPromptService system) {
    return new LlmBehaviorMessageGenerator(()->provider.chatModel(LlmFeature.ACTIVITY_BEHAVIOR),
        ()->provider.chatOptions(LlmFeature.ACTIVITY_BEHAVIOR,model.get()),system::systemPrompt);
  }
  @Bean BehaviorService behaviorService(BehaviorProperties config,ActivityProperties activity,ActivityCapture capture,ActivityStore store,
      BehaviorStateStore state,BehaviorMessageGenerator generator,AgentMessagePublisher publisher,AgentActivityTracker tracker) {
    return new BehaviorService(config,activity,capture,store,state,generator,publisher,tracker,Clock.systemUTC());
  }
  @Bean ThreadPoolTaskExecutor behaviorExecutor() {
    var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("rei-behavior-");executor.setDaemon(true);executor.setWaitForTasksToCompleteOnShutdown(false);executor.setAwaitTerminationSeconds(5);return executor;
  }
  @Bean BehaviorJob behaviorJob(BehaviorService service,@Qualifier("behaviorExecutor") ThreadPoolTaskExecutor executor) {return new BehaviorJob(service,executor);}
  public record BehaviorJob(BehaviorService service,ThreadPoolTaskExecutor executor) {
    @Scheduled(fixedDelayString="#{${rei.activity.behavior.check-interval-seconds:60} * 1000}",initialDelayString="#{${rei.activity.behavior.check-interval-seconds:60} * 1000}")
    public void poll() {
      if(!service.enabled()) return;
      try {executor.execute(service::tick);} catch(org.springframework.core.task.TaskRejectedException ignored) { /* Never queue stale notifications. */ }
    }
  }
}
