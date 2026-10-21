package dev.mikoto2000.rei.core.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 非同期の構成要素を接続し、設定に従って利用できるようにする。 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfiguration {

  /** 機能の組み立てで用いる埋め込み表現の生成タスク実行先を返す。 */
  @Bean(name = "embedTaskExecutor")
  public ThreadPoolTaskExecutor embedTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("embed-");
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(16);
    executor.initialize();
    return executor;
  }
}
