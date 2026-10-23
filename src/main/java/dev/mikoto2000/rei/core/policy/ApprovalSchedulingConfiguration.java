package dev.mikoto2000.rei.core.policy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 承認後の続行を一般の定期処理から分離する設定。 */
@Configuration(proxyBeanMethods = false)
public class ApprovalSchedulingConfiguration {
  /** 一般の自動設定を置き換えず、承認の続行と失効判定を専用スレッドで行う。 */
  @Bean(defaultCandidate = false)
  public ThreadPoolTaskScheduler approvalContinuationScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("approval-continuation-");
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }
}
